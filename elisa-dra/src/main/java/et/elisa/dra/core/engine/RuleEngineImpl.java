package et.elisa.dra.core.engine;

import et.elisa.dra.core.bind.BindingEntry;
import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.common.DraResultCodes;
import et.elisa.dra.core.lb.GroupRuntime;
import et.elisa.dra.core.lb.PeerHandle;
import et.elisa.dra.core.metrics.MetricsNames;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

public final class RuleEngineImpl implements RuleEngine {

    public static final String LOOP_KEY = "__LOOP_SELF";

    private static final int FLAG_PROXYABLE = 0x40;
    private static final int FLAG_ERROR = 0x20;
    private static final int FLAG_RETRANSMIT = 0x10;

    private record Installed(int version, String selfOriginHost, List<Rule> rules) {
        static final Installed EMPTY = new Installed(0, "dra.local", List.of());
    }

    private record CacheKey(String realm, int appId) {
    }

    private final KeyExtractor extractor;
    private final StickyLookup stickyLookup;
    private final EligibilityFn eligibilityFn;
    private final Clock clock;
    private final Consumer<String> audit;
    private final AtomicReference<Installed> installed = new AtomicReference<>(Installed.EMPTY);
    private final ConcurrentHashMap<String, GroupRuntime> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<PeerHandle>> latestCandidates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheKey, Long> redirectCache = new ConcurrentHashMap<>();

    private final LongAdder resolveTotal = new LongAdder();
    private final LongAdder forwardTotal = new LongAdder();
    private final LongAdder redirectTotal = new LongAdder();
    private final LongAdder rejectTotal = new LongAdder();
    private final LongAdder nomatchTotal = new LongAdder();
    private final LongAdder noCandidateTotal = new LongAdder();
    private final LongAdder loopDetectedTotal = new LongAdder();
    private final LongAdder stickyRebindTotal = new LongAdder();

    public RuleEngineImpl() {
        this(KeyExtractorHolder.INSTANCE, StickyLookup.empty(), EligibilityFn.all(),
                Clock.systemUTC(), s -> {
                });
    }

    public RuleEngineImpl(KeyExtractor extractor, StickyLookup stickyLookup, EligibilityFn eligibilityFn) {
        this(extractor, stickyLookup, eligibilityFn, Clock.systemUTC(), s -> {
        });
    }

    public RuleEngineImpl(KeyExtractor extractor, StickyLookup stickyLookup, EligibilityFn eligibilityFn,
                          Clock clock, Consumer<String> audit) {
        this.extractor = extractor;
        this.stickyLookup = stickyLookup;
        this.eligibilityFn = eligibilityFn;
        this.clock = clock;
        this.audit = audit;
    }

    @Override
    public RoutingContext contextFor(String ingressPeerId, DiaMsg msg) {
        Map<String, String> keys = new LinkedHashMap<>(extractor.extract(msg));
        List<DiaAvp> avps = msg.avps() == null ? List.of() : msg.avps();
        String selfHost = installed.get().selfOriginHost();
        for (DiaAvp a : avps) {
            if (a.code() == AvpCodes.ROUTE_RECORD && a.vendorId() == 0
                    && a.typeIndex() == DiaAvp.TYPE_UTF8 && a.value() instanceof String host
                    && host.equals(selfHost)) {
                keys.put(LOOP_KEY, "1");
                break;
            }
        }
        long drmp = RoutingContext.DRMP_DEFAULT;
        for (DiaAvp a : avps) {
            if (a.code() == AvpCodes.DRMP && a.vendorId() == 0) {
                Long v = KeyExtractorImpl.uint32Value(a);
                if (v != null) {
                    drmp = v;
                    break;
                }
            }
        }
        return new RoutingContext(ingressPeerId, msg.applicationId(), msg.commandCode(),
                msg.isRequest(), (msg.flags() & FLAG_PROXYABLE) != 0,
                (msg.flags() & FLAG_ERROR) == 0 ? 0 : 1, (msg.flags() & FLAG_RETRANSMIT) == 0 ? 0 : 1,
                (int) drmp, msg.destinationHost(), msg.destinationRealm(),
                msg.originHost(), msg.originRealm(), Map.copyOf(keys));
    }

    @Override
    public RouteDecision resolve(RoutingContext ctx) {
        resolveTotal.increment();
        if ("1".equals(ctx.key(LOOP_KEY))) {
            loopDetectedTotal.increment();
            return new RouteDecision.Reject(DraResultCodes.LOOP_DETECTED, "loop-detected");
        }
        Installed inst = installed.get();
        for (Rule rule : inst.rules()) {
            if (rule.when().evaluate(ctx)) {
                return materialize(rule.then(), ctx);
            }
        }
        nomatchTotal.increment();
        return new RouteDecision.Reject(DraResultCodes.UNABLE_TO_DELIVER, "no-route");
    }

    public void installRuleSet(et.elisa.dra.core.cfg.RuleSet rs) {
        List<Rule> rules = rs == null ? List.of() : rs.rules();
        installed.set(new Installed(rs == null ? 0 : rs.version(),
                rs == null ? "dra.local" : rs.selfOriginHost(), rules));
        currentRuleSet = rs;
        reconcileGroups(rs);
    }

    private void reconcileGroups(et.elisa.dra.core.cfg.RuleSet rs) {
        if (rs == null || rs.groups().isEmpty()) {
            groups.clear();
            return;
        }
        groups.keySet().retainAll(rs.groups().keySet());
        for (Map.Entry<String, et.elisa.dra.core.cfg.RuleSet.GroupSpec> e : rs.groups().entrySet()) {
            et.elisa.dra.core.cfg.RuleSet.GroupSpec spec = e.getValue();
            GroupRuntime existing = groups.get(spec.name());
            if (existing == null || !existing.sameShape(spec.strategy(), spec.failoverEnabled(), spec.maxRetries())) {
                groups.put(spec.name(), new GroupRuntime(spec.name(), spec.strategy(),
                        spec.failoverEnabled(), spec.maxRetries()));
            }
        }
        applyLatestCandidates();
    }

    private void applyLatestCandidates() {
        latestCandidates.forEach((groupId, handles) -> {
            GroupRuntime gr = groups.get(groupId);
            if (gr != null) {
                gr.updateCandidates(handles);
            }
        });
    }

    public void updateCandidates(String groupId, List<PeerHandle> handles) {
        latestCandidates.put(groupId, List.copyOf(handles));
        GroupRuntime gr = groups.get(groupId);
        if (gr != null) {
            gr.updateCandidates(latestCandidates.get(groupId));
        }
    }

    public GroupRuntime group(String groupId) {
        return groups.get(groupId);
    }

    public Set<String> groupIds() {
        return Set.copyOf(groups.keySet());
    }

    private RouteDecision materialize(Action action, RoutingContext ctx) {
        if (action instanceof Action.Forward f) {
            return materializeForward(f, ctx);
        }
        if (action instanceof Action.Redirect r) {
            redirectTotal.increment();
            if (r.cacheSeconds() > 0) {
                redirectCache.put(new CacheKey(r.realm(), ctx.applicationId()),
                        clock.millis() + r.cacheSeconds() * 1000L);
            }
            return new RouteDecision.Redirect(r.host(), r.realm(), r.cacheSeconds());
        }
        if (action instanceof Action.Reject j) {
            rejectTotal.increment();
            return new RouteDecision.Reject(j.resultCode(), j.reason());
        }
        nomatchTotal.increment();
        return new RouteDecision.Reject(DraResultCodes.UNABLE_TO_DELIVER, "unroutable");
    }

    private RouteDecision materializeForward(Action.Forward f, RoutingContext ctx) {
        GroupRuntime gr = groups.get(f.group());
        if (gr == null) {
            noCandidateTotal.increment();
            return new RouteDecision.Reject(DraResultCodes.UNABLE_TO_DELIVER,
                    "unknown-group:" + f.group());
        }
        String preferred = null;
        StickyBinding stickyOut = null;
        if (f.sticky() != null) {
            String kv = ctx.key(f.sticky().key());
            if (kv != null && !kv.isBlank()) {
                String storeKey = f.sticky().key() + ":" + kv;
                stickyOut = new StickyBinding(storeKey, f.sticky().ttlSeconds());
                var hit = stickyLookup.get(storeKey);
                if (hit.isPresent()) {
                    BindingEntry e = hit.get();
                    if (f.group().equals(e.groupId())) {
                        preferred = e.peerId();
                    } else {
                        stickyRebindTotal.increment();
                        audit.accept("sticky-rebinding key=" + storeKey + " from=" + e.groupId()
                                + " to=" + f.group());
                    }
                }
            }
        }
        List<PeerHandle> eligible = new ArrayList<>();
        for (PeerHandle p : gr.candidates()) {
            if (!eligibilityFn.eligible(p, ctx.applicationId())) {
                continue;
            }
            if (!f.allowHairpin() && p.peerId().equals(ctx.ingressPeerId())) {
                continue;
            }
            eligible.add(p);
        }
        if (eligible.isEmpty()) {
            noCandidateTotal.increment();
            return new RouteDecision.Reject(DraResultCodes.UNABLE_TO_DELIVER, "unable-to-deliver");
        }
        PeerHandle chosen = gr.choose(eligible, preferred);
        forwardTotal.increment();
        return new RouteDecision.Forward(f.group(), stickyOut, gr.failoverEnabled(),
                f.th() == null ? ThMode.OFF : f.th(), withRouteRecord(f.ops()), chosen.peerId());
    }

    private List<AvpOp> withRouteRecord(List<AvpOp> ops) {
        for (AvpOp op : ops) {
            if (op instanceof AvpOp.AppendRouteRecord) {
                return ops;
            }
        }
        List<AvpOp> out = new ArrayList<>(ops.size() + 1);
        out.addAll(ops);
        out.add(new AvpOp.AppendRouteRecord(installed.get().selfOriginHost()));
        return List.copyOf(out);
    }

    public boolean redirectCacheActive(String realm, int appId) {
        CacheKey k = new CacheKey(realm, appId);
        Long expiry = redirectCache.get(k);
        if (expiry == null) {
            return false;
        }
        if (expiry <= clock.millis()) {
            redirectCache.remove(k, expiry);
            return false;
        }
        return true;
    }

    public Map<String, Long> redirectCacheSnapshot() {
        Map<String, Long> out = new HashMap<>();
        long now = clock.millis();
        redirectCache.forEach((k, v) -> out.put(k.realm() + "|" + k.appId(), Math.max(0, v - now)));
        return out;
    }

    public Map<String, Long> counters() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(MetricsNames.TX_TOTAL, resolveTotal.sum());
        m.put(MetricsNames.ROUTE_NOMATCH, nomatchTotal.sum());
        m.put("dra_route_forward_total", forwardTotal.sum());
        m.put("dra_route_redirect_total", redirectTotal.sum());
        m.put("dra_route_reject_total", rejectTotal.sum());
        m.put("dra_route_no_candidate_total", noCandidateTotal.sum());
        m.put("dra_route_loop_detected_total", loopDetectedTotal.sum());
        m.put("dra_sticky_rebind_total", stickyRebindTotal.sum());
        return m;
    }

    private volatile et.elisa.dra.core.cfg.RuleSet currentRuleSet;

    public et.elisa.dra.core.cfg.RuleSet currentRuleSet() {
        return currentRuleSet;
    }

    private static final class KeyExtractorHolder {
        static final KeyExtractor INSTANCE = new KeyExtractorImpl();
    }
}
