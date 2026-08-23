package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.bind.BindingKeys;
import et.elisa.dra.core.bind.PeerRouteTarget;
import et.elisa.dra.core.bind.InMemoryBindingStore;
import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.common.DraResultCodes;
import et.elisa.dra.core.engine.StickyBinding;
import et.elisa.dra.core.metrics.MetricsNames;
import et.elisa.dra.core.tx.DefaultTxTable;
import et.elisa.dra.core.tx.HbhAllocator;
import et.elisa.dra.core.tx.RelaySupport;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayCoreTest {

    static final String SELF = "dra-01.epc.mnc01.mcc452.3gppnetwork.org";
    static final String REALM = "epc.mnc01.mcc452.3gppnetwork.org";
    static final String MME_LINK = "mme-01";

    static final class Stack {
        static final long BASE_MILLIS = System.currentTimeMillis() - 60_000L;
        final AtomicLong now = new AtomicLong(BASE_MILLIS);
        final DefaultTxTable table = new DefaultTxTable();
        final FakeRaPort ra = new FakeRaPort();
        final InMemoryBindingStore bindings = new InMemoryBindingStore();
        final FakeEngine engine = new FakeEngine(SELF);
        final AtomicReference<Optional<PeerRouteTarget>> siTarget =
                new AtomicReference<>(Optional.empty());
        final PassGate gate = new PassGate();
        final PassScreen screen = new PassScreen();
        final FakeTh th = new FakeTh();
        final FixedCandidates candidates = new FixedCandidates();

        RelayCore core() {
            return new RelayCore(engine, table, new HbhAllocator(), ra, bindings,
                    ctx -> siTarget.get(), gate, screen, th, candidates, SELF,
                    new RelaySupport(1_000, 1), now::get);
        }
    }

    static DiaMsg request(int cmd, long hbh, long e2e, String session, String originHost) {
        return new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, cmd, 16777216,
                hbh, e2e, session, originHost, REALM, null, REALM, 0, List.of());
    }

    private static DiaMsg ulr(long hbh) {
        return new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, 316, 16777216,
                hbh, 770_077L, "sess-ulr-1", "mme-01.epc.mnc01.mcc452.3gppnetwork.org", REALM,
                null, REALM, 0, List.of(DiaAvp.utf8(AvpCodes.USER_NAME, "452040210000001")));
    }

    @Test
    void forwardsToPreferredPeerWithFreshHbhAndRouteRecord() {
        Stack s = new Stack();
        RelayCore core = s.core();
        DiaMsg req = ulr(1001);

        core.onRequest(MME_LINK, req);

        assertEquals(1, s.ra.requests.size());
        FakeRaPort.Sent sent = s.ra.requests.get(0);
        assertEquals("hss-a", sent.peerId());
        assertNotEquals(1001L, sent.msg().hopByHopId());
        assertTrue(sent.msg().hopByHopId() > 0);
        assertEquals(Optional.of(SELF), AvpOps.firstUtf8(sent.msg(), AvpCodes.ROUTE_RECORD));
        assertEquals(1, s.table.activeCount());
        assertNotNull(s.table.byHbhOut(sent.msg().hopByHopId()));
        assertEquals(1, core.metrics().value(MetricsNames.TX_TOTAL));
    }

    @Test
    void answerCorrelatesBackToIngressWithOriginalHbh() {
        Stack s = new Stack();
        RelayCore core = s.core();
        DiaMsg req = ulr(1001);
        core.onRequest(MME_LINK, req);
        long hbhOut = s.ra.requests.get(0).msg().hopByHopId();

        core.onAnswer(req.asAnswer(DraResultCodes.SUCCESS).withHopByHop(hbhOut), "hss-a");

        assertEquals(1, s.ra.answers.size());
        FakeRaPort.Sent back = s.ra.answers.get(0);
        assertEquals(MME_LINK, back.peerId());
        assertEquals(1001L, back.msg().hopByHopId());
        assertEquals(DraResultCodes.SUCCESS, back.msg().resultCode());
        assertEquals(0, s.table.activeCount());
        assertTrue(s.gate.answeredFrom.contains("hss-a"));
    }

    @Test
    void purTimeoutFailoversToSecondPeerThenSucceeds() {
        Stack s = new Stack();
        s.candidates.byGroup.put("pur-pool", List.of("hss-a", "hss-b"));
        s.engine.forwardTo("pur-pool", "hss-a");
        RelayCore core = s.core();
        DiaMsg req = request(321, 5001, 900, "sess-pur-1", "mme-01.host");
        core.onRequest(MME_LINK, req);
        long firstHbh = s.ra.requests.get(0).msg().hopByHopId();

        s.now.addAndGet(2_000);
        core.sweep(s.now.get());

        assertEquals(2, s.ra.requests.size());
        FakeRaPort.Sent retry = s.ra.requests.get(1);
        assertEquals("hss-b", retry.peerId());
        assertNotEquals(firstHbh, retry.msg().hopByHopId());
        assertEquals(900L, retry.msg().endToEndId());
        assertEquals(1, core.metrics().value(MetricsNames.FAILOVER_TOTAL));

        core.onAnswer(req.asAnswer(DraResultCodes.SUCCESS).withHopByHop(retry.msg().hopByHopId()), "hss-b");

        assertEquals(1, s.ra.answers.size());
        assertEquals(MME_LINK, s.ra.answers.get(0).peerId());
        assertEquals(5001L, s.ra.answers.get(0).msg().hopByHopId());
        assertEquals(0, s.table.activeCount());
    }

    @Test
    void exhaustedRetriesGiveUpWithUnableToDeliver() {
        Stack s = new Stack();
        s.candidates.byGroup.put("pur-pool", List.of("hss-a", "hss-b"));
        s.engine.forwardTo("pur-pool", "hss-a");
        RelayCore core = s.core();
        DiaMsg req = request(321, 6001, 901, "sess-pur-2", "mme-01.host");
        core.onRequest(MME_LINK, req);

        s.now.addAndGet(2_000);
        core.sweep(s.now.get());
        assertEquals(2, s.ra.requests.size());
        assertEquals("hss-b", s.ra.requests.get(1).peerId());

        s.now.addAndGet(2_000);
        core.sweep(s.now.get());

        assertEquals(2, s.ra.requests.size());
        assertEquals(1, s.ra.answers.size());
        FakeRaPort.Sent fail = s.ra.answers.get(0);
        assertEquals(MME_LINK, fail.peerId());
        assertEquals(6001L, fail.msg().hopByHopId());
        assertEquals(DraResultCodes.UNABLE_TO_DELIVER, fail.msg().resultCode());
        assertEquals(0, s.table.activeCount());
        assertEquals(1, core.metrics().value(SbbMetrics.UNDELIVERED_TOTAL));
    }

    @Test
    void sendFailureIsTreatedAsImmediateTimeoutPath() {
        Stack s = new Stack();
        s.candidates.byGroup.put("pur-pool", List.of("hss-a", "hss-b"));
        s.engine.forwardTo("pur-pool", "hss-a");
        s.ra.failingPeers.add("hss-a");
        RelayCore core = s.core();
        DiaMsg req = request(321, 6101, 902, "sess-pur-3", "mme-01.host");

        core.onRequest(MME_LINK, req);

        assertEquals(1, s.ra.requests.size());
        assertEquals("hss-b", s.ra.requests.get(0).peerId());
        assertEquals(1, core.metrics().value(MetricsNames.FAILOVER_TOTAL));
        assertEquals(1, core.metrics().value(SbbMetrics.SEND_FAILED_TOTAL));
        assertEquals(1, s.table.activeCount());
    }

    @Test
    void nonRetryableTimeoutAnswersUnableToDeliver() {
        Stack s = new Stack();
        s.engine.forwardTo("pcrf-pool", "pcrf-a");
        RelayCore core = s.core();
        DiaMsg req = request(272, 7001, 950, "sess-ccr-1", "mme-01.host");

        core.onRequest(MME_LINK, req);
        s.now.addAndGet(5_000);
        core.sweep(s.now.get());

        assertEquals(1, s.ra.requests.size());
        assertEquals(1, s.ra.answers.size());
        FakeRaPort.Sent fail = s.ra.answers.get(0);
        assertEquals(MME_LINK, fail.peerId());
        assertEquals(7001L, fail.msg().hopByHopId());
        assertEquals(DraResultCodes.UNABLE_TO_DELIVER, fail.msg().resultCode());
        assertEquals(0, s.table.activeCount());
    }

    @Test
    void redirectAnswersWithHostAndCacheTime() {
        Stack s = new Stack();
        s.engine.decision.set(new et.elisa.dra.core.engine.RouteDecision.Redirect(
                "hss-x.example.org", REALM, 60));
        RelayCore core = s.core();

        core.onRequest(MME_LINK, ulr(8001));

        assertTrue(s.ra.requests.isEmpty());
        assertEquals(1, s.ra.answers.size());
        FakeRaPort.Sent ans = s.ra.answers.get(0);
        assertEquals(MME_LINK, ans.peerId());
        assertEquals(DraResultCodes.REDIRECT_INDICATION, ans.msg().resultCode());
        assertEquals(Optional.of("hss-x.example.org"), AvpOps.firstUtf8(ans.msg(), AvpCodes.REDIRECT_HOST));
        assertEquals(Optional.of(60L), AvpOps.firstUint32(ans.msg(), AvpCodes.REDIRECT_MAX_CACHE_TIME));
        assertEquals(1, core.metrics().value(SbbMetrics.REDIRECT_TOTAL));
    }

    @Test
    void capturesImsiBindingOnUlaSuccess() {
        Stack s = new Stack();
        String key = BindingKeys.IMSI + ":452040210000001";
        s.engine.decision.set(new et.elisa.dra.core.engine.RouteDecision.Forward(
                "mvno-hss-pool", new StickyBinding(key, 3600), true,
                et.elisa.dra.core.engine.ThMode.OFF, List.of(), "hss-a"));
        RelayCore core = s.core();
        DiaMsg req = ulr(9001);

        core.onRequest(MME_LINK, req);
        long hbhOut = s.ra.requests.get(0).msg().hopByHopId();
        core.onAnswer(req.asAnswer(DraResultCodes.SUCCESS).withHopByHop(hbhOut), "hss-a");

        Optional<et.elisa.dra.core.bind.BindingEntry> hit = s.bindings.get(key);
        assertTrue(hit.isPresent());
        et.elisa.dra.core.bind.BindingEntry entry = hit.orElseThrow();
        assertEquals("hss-a", entry.peerId());
        assertEquals("mvno-hss-pool", entry.groupId());
        assertEquals(MME_LINK, entry.ingressPeerId());
        assertEquals("mme-01.epc.mnc01.mcc452.3gppnetwork.org", entry.originHost());
        Instant expiresAt = entry.expiresAt();
        assertTrue(expiresAt.isAfter(Instant.ofEpochMilli(s.now.get()).plusSeconds(3599)));
        assertEquals(1, core.metrics().value(SbbMetrics.BINDING_CAPTURED_TOTAL));
        assertEquals(0, s.table.activeCount());
    }

    @Test
    void nonSuccessAnswerDoesNotCaptureBinding() {
        Stack s = new Stack();
        String key = BindingKeys.IMSI + ":452040210000002";
        s.engine.decision.set(new et.elisa.dra.core.engine.RouteDecision.Forward(
                "mvno-hss-pool", new StickyBinding(key, 3600), true,
                et.elisa.dra.core.engine.ThMode.OFF, List.of(), "hss-a"));
        RelayCore core = s.core();
        DiaMsg req = ulr(9101);

        core.onRequest(MME_LINK, req);
        long hbhOut = s.ra.requests.get(0).msg().hopByHopId();
        core.onAnswer(req.asAnswer(DraResultCodes.UNABLE_TO_COMPLY).withHopByHop(hbhOut), "hss-a");

        assertTrue(s.bindings.get(key).isEmpty());
        assertEquals(0, core.metrics().value(SbbMetrics.BINDING_CAPTURED_TOTAL));
        assertEquals(1, s.ra.answers.size());
    }

    @Test
    void unknownHbhAnswerIsDropped() {
        Stack s = new Stack();
        RelayCore core = s.core();

        core.onAnswer(ulr(987_654_321L).asAnswer(DraResultCodes.SUCCESS), "hss-a");

        assertTrue(s.ra.answers.isEmpty());
        assertTrue(s.ra.requests.isEmpty());
        assertEquals(1, core.metrics().value(SbbMetrics.DROP_UNKNOWN_TX_TOTAL));
    }

    static String self() {
        return SELF;
    }

    static String realm() {
        return REALM;
    }

    static String mmeLink() {
        return MME_LINK;
    }
}
