package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.bind.BindingEntry;
import et.elisa.dra.core.bind.BindingStore;
import et.elisa.dra.core.bind.PeerRouteTarget;
import et.elisa.dra.core.bind.ServerInitiatedResolver;
import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.common.DraResultCodes;
import et.elisa.dra.core.common.RetryableCommands;
import et.elisa.dra.core.engine.RouteDecision;
import et.elisa.dra.core.engine.RuleEngine;
import et.elisa.dra.core.engine.RoutingContext;
import et.elisa.dra.core.engine.StickyBinding;
import et.elisa.dra.core.engine.ThMode;
import et.elisa.dra.core.metrics.MetricsNames;
import et.elisa.dra.core.overload.OverloadGate;
import et.elisa.dra.core.peer.DraRaPort;
import et.elisa.dra.core.screen.Screener;
import et.elisa.dra.core.th.TopologyHider;
import et.elisa.dra.core.tx.HbhAllocator;
import et.elisa.dra.core.tx.RelaySupport;
import et.elisa.dra.core.tx.TxState;
import et.elisa.dra.core.tx.TxTable;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class RelayCore {

    private static final Set<Integer> CAPTURE_COMMANDS = Set.of(
            RetryableCommands.CMD_ULR,
            RetryableCommands.CMD_AIR,
            RetryableCommands.CMD_PUR,
            RetryableCommands.CMD_NOR);

    private final RuleEngine engine;
    private final TxTable txTable;
    private final HbhAllocator hbhAllocator;
    private final DraRaPort raPort;
    private final BindingStore bindings;
    private final ServerInitiatedResolver resolver;
    private final OverloadGate overload;
    private final Screener screener;
    private final TopologyHider topologyHider;
    private final CandidateSource candidates;
    private final String selfOriginHost;
    private final RelaySupport support;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Long, PendingForward> pending = new ConcurrentHashMap<>();
    private final SbbMetrics metrics = new SbbMetrics();

    public RelayCore(RuleEngine engine, TxTable txTable, HbhAllocator hbhAllocator,
                     DraRaPort raPort, BindingStore bindings, ServerInitiatedResolver resolver,
                     OverloadGate overload, Screener screener, TopologyHider topologyHider,
                     CandidateSource candidates, String selfOriginHost, long twMillis,
                     int maxRetries) {
        this(engine, txTable, hbhAllocator, raPort, bindings, resolver, overload, screener,
                topologyHider, candidates, selfOriginHost,
                new RelaySupport(twMillis, maxRetries), System::currentTimeMillis);
    }

    RelayCore(RuleEngine engine, TxTable txTable, HbhAllocator hbhAllocator,
              DraRaPort raPort, BindingStore bindings, ServerInitiatedResolver resolver,
              OverloadGate overload, Screener screener, TopologyHider topologyHider,
              CandidateSource candidates, String selfOriginHost, RelaySupport support,
              LongSupplier clock) {
        this.engine = engine;
        this.txTable = txTable;
        this.hbhAllocator = hbhAllocator;
        this.raPort = raPort;
        this.bindings = bindings;
        this.resolver = resolver;
        this.overload = overload;
        this.screener = screener;
        this.topologyHider = topologyHider;
        this.candidates = candidates;
        this.selfOriginHost = selfOriginHost;
        this.support = support;
        this.clock = clock;
    }

    public SbbMetrics metrics() {
        return metrics;
    }

    public long nowMillis() {
        return clock.getAsLong();
    }

    public void onRequest(String ingressPeerId, DiaMsg req) {
        if (guardRejected(ingressPeerId, req)) {
            return;
        }
        RoutingContext ctx = engine.contextFor(ingressPeerId, req);
        handleDecision(engine.resolve(ctx), ingressPeerId, req, true);
    }

    public void serverInitiated(String ingressPeerId, DiaMsg req) {
        if (guardRejected(ingressPeerId, req)) {
            return;
        }
        RoutingContext ctx = engine.contextFor(ingressPeerId, req);
        Optional<PeerRouteTarget> target = resolver.resolve(ctx);
        if (target.isPresent()) {
            serverInitiatedForward(target.get(), ingressPeerId, req);
            return;
        }
        if (isBlank(req.destinationHost())) {
            metrics.inc(SbbMetrics.SERVER_INITIATED_FAIL_CLOSED_TOTAL);
            answerIngress(ingressPeerId, req, DraResultCodes.UNABLE_TO_DELIVER);
            return;
        }
        handleDecision(engine.resolve(ctx), ingressPeerId, req, false);
    }

    public void onAnswer(DiaMsg ans, String egressPeerId) {
        TxState tx = txTable.byHbhOut(ans.hopByHopId());
        if (tx == null) {
            metrics.inc(SbbMetrics.DROP_UNKNOWN_TX_TOTAL);
            return;
        }
        overload.onAnswer(ans, egressPeerId);
        int rc = resultCodeOf(ans);
        countAnswerClass(rc);
        PendingForward p = pending.get(tx.hbhOut);
        captureBindingIfDue(tx, p, rc, egressPeerId);
        DiaMsg out = ans.withHopByHop(tx.hbhIn);
        if (p != null && p.thEnabled()) {
            out = topologyHider.restoreInbound(out);
        }
        tx.answered = true;
        release(tx.hbhOut);
        raPort.sendAnswerOnLink(tx.ingressPeerId, out);
    }

    public void onExpired(TxState tx) {
        PendingForward p = pending.get(tx.hbhOut);
        if (p == null) {
            release(tx.hbhOut);
            return;
        }
        if (support.canRetry(tx.commandCode, tx.retryCount)) {
            String next = pickCandidate(p.groupId(), tx);
            if (next != null) {
                failoverTo(tx, p, next);
                return;
            }
        }
        giveUp(tx, p);
    }

    public void sweep(long nowMillis) {
        txTable.forEachExpired(nowMillis, this::onExpired);
    }

    private boolean guardRejected(String ingressPeerId, DiaMsg req) {
        Optional<Integer> violation = screener.ingressCheck(req, ingressPeerId);
        if (violation.isPresent()) {
            metrics.inc(SbbMetrics.SCREEN_REJECTED_TOTAL);
            answerIngress(ingressPeerId, req, violation.get());
            return true;
        }
        if (AvpOps.stringsOf(req, AvpCodes.ROUTE_RECORD).contains(selfOriginHost)) {
            metrics.inc(SbbMetrics.LOOP_DETECTED_TOTAL);
            answerIngress(ingressPeerId, req, DraResultCodes.LOOP_DETECTED);
            return true;
        }
        if (!overload.admit(ingressPeerId, AvpOps.drmpPriority(req))) {
            metrics.inc(MetricsNames.THROTTLED_TOTAL);
            answerIngress(ingressPeerId, req, DraResultCodes.TOO_BUSY);
            return true;
        }
        return false;
    }

    private void handleDecision(RouteDecision decision, String ingressPeerId, DiaMsg req,
                                boolean bindingCaptureAllowed) {
        switch (decision) {
            case RouteDecision.Forward f -> standardForward(f, ingressPeerId, req, bindingCaptureAllowed);
            case RouteDecision.Redirect r -> redirectAnswer(r, ingressPeerId, req);
            case RouteDecision.Reject rej -> {
                metrics.inc(SbbMetrics.REJECT_TOTAL);
                answerIngress(ingressPeerId, req, rej.resultCode());
            }
        }
    }

    private void standardForward(RouteDecision.Forward f, String ingressPeerId, DiaMsg req,
                                 boolean bindingCaptureAllowed) {
        StickyBinding sticky = f.sticky();
        String stickyKeyFull = sticky != null ? sticky.key() : null;
        Optional<BindingEntry> stickyHit = stickyKeyFull != null ? bindings.get(stickyKeyFull) : Optional.empty();
        ThMode mode = f.th();
        boolean thEnabled = mode != ThMode.OFF;
        DiaMsg body = thEnabled ? topologyHider.hideOutbound(req, stickyValueOf(stickyKeyFull)) : req;
        body = AvpOps.apply(body, f.ops());
        String egress = stickyHit.map(BindingEntry::peerId).orElse(f.preferredPeerId());
        if (isBlank(egress)) {
            answerIngress(ingressPeerId, req, DraResultCodes.UNABLE_TO_DELIVER);
            return;
        }
        registerAndSend(ingressPeerId, req, body, egress, f.group(),
                bindingCaptureAllowed ? stickyKeyFull : null,
                sticky != null ? sticky.ttlSeconds() : 0L,
                req.originHost(), req.originRealm(), thEnabled);
    }

    private void serverInitiatedForward(PeerRouteTarget target, String ingressPeerId, DiaMsg req) {
        boolean thEnabled = topologyHider.enabledForGroup(target.groupId());
        DiaMsg body = thEnabled ? topologyHider.restoreInbound(req) : req;
        if (!isBlank(target.destHostRewrite())) {
            body = AvpOps.withDestinationHost(body, target.destHostRewrite());
        }
        if (isBlank(target.preferredPeerId())) {
            answerIngress(ingressPeerId, req, DraResultCodes.UNABLE_TO_DELIVER);
            return;
        }
        registerAndSend(ingressPeerId, req, body, target.preferredPeerId(), target.groupId(),
                null, 0L, req.originHost(), req.originRealm(), thEnabled);
    }

    private void redirectAnswer(RouteDecision.Redirect r, String ingressPeerId, DiaMsg req) {
        metrics.inc(SbbMetrics.REDIRECT_TOTAL);
        DiaMsg ans = req.asAnswer(DraResultCodes.REDIRECT_INDICATION);
        ans = AvpOps.append(ans, DiaAvp.utf8(AvpCodes.REDIRECT_HOST, r.host()));
        ans = AvpOps.append(ans, DiaAvp.uint32(AvpCodes.REDIRECT_MAX_CACHE_TIME, r.cacheSeconds()));
        countAnswerClass(DraResultCodes.REDIRECT_INDICATION);
        raPort.sendAnswerOnLink(ingressPeerId, ans);
    }

    private void registerAndSend(String ingressPeerId, DiaMsg originalRequest, DiaMsg body,
                                 String egressPeerId, String groupId, String stickyKeyFull,
                                 long ttlSeconds, String originHost, String originRealm,
                                 boolean thEnabled) {
        long hbhOut = allocateHbh();
        TxState tx = new TxState();
        tx.hbhIn = originalRequest.hopByHopId();
        tx.hbhOut = hbhOut;
        tx.e2eIn = originalRequest.endToEndId();
        tx.e2eOut = originalRequest.endToEndId();
        tx.ingressPeerId = ingressPeerId;
        tx.egressPeerId = egressPeerId;
        tx.applicationId = originalRequest.applicationId();
        tx.commandCode = originalRequest.commandCode();
        tx.sessionId = originalRequest.sessionId();
        tx.drmpPriority = AvpOps.drmpPriority(originalRequest);
        tx.deadlineMillis = support.deadlineFrom(clock.getAsLong());
        pending.put(hbhOut, new PendingForward(originalRequest, body, stickyKeyFull, groupId,
                ttlSeconds, originHost, originRealm, thEnabled));
        txTable.put(tx);
        metrics.inc(MetricsNames.TX_TOTAL);
        try {
            raPort.sendToPeer(egressPeerId, body.withHopByHop(hbhOut));
        } catch (RuntimeException e) {
            metrics.inc(SbbMetrics.SEND_FAILED_TOTAL);
            onExpired(tx);
        }
    }

    private void captureBindingIfDue(TxState tx, PendingForward p, int resultCode, String egressPeerId) {
        if (p == null || p.stickyKeyFull() == null) {
            return;
        }
        if (resultCode != DraResultCodes.SUCCESS || !CAPTURE_COMMANDS.contains(tx.commandCode)) {
            return;
        }
        Instant now = Instant.ofEpochMilli(clock.getAsLong());
        bindings.put(new BindingEntry(p.stickyKeyFull(), p.groupId(), egressPeerId,
                p.originHost(), p.originRealm(), tx.ingressPeerId, now,
                now.plus(Duration.ofSeconds(p.ttlSeconds()))));
        metrics.inc(SbbMetrics.BINDING_CAPTURED_TOTAL);
    }

    private void failoverTo(TxState tx, PendingForward p, String nextPeer) {
        long oldHbh = tx.hbhOut;
        String oldPeer = tx.egressPeerId;
        release(oldHbh);
        tx.triedPeers.add(oldPeer);
        tx.retryCount++;
        long newHbh = allocateHbh();
        tx.hbhOut = newHbh;
        tx.egressPeerId = nextPeer;
        tx.deadlineMillis = support.deadlineFrom(clock.getAsLong());
        pending.put(newHbh, p);
        txTable.put(tx);
        metrics.inc(MetricsNames.FAILOVER_TOTAL);
        try {
            raPort.sendToPeer(nextPeer, p.outboundBody().withHopByHop(newHbh));
        } catch (RuntimeException e) {
            metrics.inc(SbbMetrics.SEND_FAILED_TOTAL);
            onExpired(tx);
        }
    }

    private void giveUp(TxState tx, PendingForward p) {
        release(tx.hbhOut);
        metrics.inc(SbbMetrics.UNDELIVERED_TOTAL);
        answerIngress(tx.ingressPeerId, p.originalRequest(), DraResultCodes.UNABLE_TO_DELIVER);
    }

    private String pickCandidate(String groupId, TxState tx) {
        List<String> list = candidates.candidatesOf(groupId, Set.copyOf(tx.triedPeers));
        for (String c : list) {
            if (!tx.tried(c) && !c.equals(tx.egressPeerId)) {
                return c;
            }
        }
        return null;
    }

    private long allocateHbh() {
        return hbhAllocator.next(v -> txTable.byHbhOut(v) != null || pending.containsKey(v));
    }

    private void release(long hbhOut) {
        pending.remove(hbhOut);
        txTable.remove(hbhOut);
    }

    private void answerIngress(String ingressPeerId, DiaMsg request, int resultCode) {
        countAnswerClass(resultCode);
        raPort.sendAnswerOnLink(ingressPeerId, request.asAnswer(resultCode));
    }

    private void countAnswerClass(int resultCode) {
        String name = switch (resultCode / 1000) {
            case 2 -> MetricsNames.ANSWER_2XX;
            case 3 -> MetricsNames.ANSWER_3XX;
            case 4 -> MetricsNames.ANSWER_4XX;
            default -> MetricsNames.ANSWER_5XX;
        };
        metrics.inc(name);
    }

    private static int resultCodeOf(DiaMsg ans) {
        if (ans.resultCode() > 0) {
            return ans.resultCode();
        }
        return AvpOps.firstUint32(ans, AvpCodes.RESULT_CODE).map(Long::intValue).orElse(0);
    }

    private static String stickyValueOf(String stickyKeyFull) {
        if (stickyKeyFull == null) {
            return null;
        }
        int idx = stickyKeyFull.indexOf(':');
        return idx >= 0 && idx < stickyKeyFull.length() - 1 ? stickyKeyFull.substring(idx + 1) : stickyKeyFull;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
