package et.elisa.dra.core.engine;

import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static et.elisa.dra.core.engine.Fixtures.S6A_APP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineResolveTest {

    private RuleEngineImpl engine(List<Rule> rules) {
        RuleEngineImpl e = new RuleEngineImpl();
        e.installRuleSet(new et.elisa.dra.core.cfg.RuleSet(1, "dra1.elisa.lab",
                java.util.Set.of("epc.lab"), rules, Map.of()));
        return e;
    }

    @Test
    void priorityOrderFirstMatchWins() {
        RuleEngineImpl e = engine(List.of(
                new Rule("later", 200, Matcher.Always.TRUE,
                        new Action.Forward("g2", null, ThMode.OFF, false, List.of())),
                new Rule("first", 100, new Matcher.HasApp(S6A_APP),
                        new Action.Reject(3002, "matched-first")),
                new Rule("never", 300, Matcher.Always.TRUE,
                        new Action.Reject(5012, "never"))));
        RoutingContext c = e.contextFor("mme-1", Fixtures.ulr("452040123456789",
                "mme-01.epc.lab", "epc.lab", "epc.lab", null));
        RouteDecision d = e.resolve(c);
        assertTrue(d instanceof RouteDecision.Reject);
        assertEquals(3002, ((RouteDecision.Reject) d).resultCode());
        assertEquals("matched-first", ((RouteDecision.Reject) d).reason());
    }

    @Test
    void nomatchFailsClosedWith3002AndCounter() {
        RuleEngineImpl e = engine(List.of(new Rule("gx-only", 100,
                new Matcher.HasApp(16777238), new Action.Reject(3003, "gx"))));
        RouteDecision d = e.resolve(e.contextFor("mme-1", Fixtures.ulr("452040000000001",
                "mme-1", "epc.lab", "epc.lab", null)));
        assertEquals(et.elisa.dra.core.common.DraResultCodes.UNABLE_TO_DELIVER,
                ((RouteDecision.Reject) d).resultCode());
        assertTrue(e.counters()
                .getOrDefault(et.elisa.dra.core.metrics.MetricsNames.ROUTE_NOMATCH, 0L) >= 1);
    }

    @Test
    void contextForExtractsDrmpFlagsAndHeaderKeys() {
        RuleEngineImpl e = new RuleEngineImpl();
        DiaMsg msg = Fixtures.ulr(null, "mme-1.epc.lab", "epc.lab", "hss.epc.lab",
                List.of(Fixtures.drmp(4)));
        RoutingContext c = e.contextFor("link-mme", msg);
        assertEquals(4, c.drmpPriority());
        assertTrue(c.isRequest());
        assertTrue(c.proxiable());
        assertFalse(c.errorBit() != 0);
        assertEquals("mme-1.epc.lab", c.origHost());
        assertEquals("epc.lab", c.origRealm());
        assertEquals("hss.epc.lab", c.destRealm());
        assertEquals("link-mme", c.ingressPeerId());
        assertEquals(RoutingContext.DRMP_DEFAULT,
                e.contextFor("link-mme", Fixtures.ulr(null, "mme-1", "epc.lab",
                        "hss.epc.lab", null)).drmpPriority());

        DiaMsg errAnswer = new DiaMsg(1, DiaMsg.FLAG_ERROR, 316, S6A_APP, 1L, 2L,
                "s", "hss-a", "epc.lab", null, "epc.lab", 5012, List.of());
        assertTrue(e.contextFor("link-mme", errAnswer).errorBit() != 0);
    }

    @Test
    void routeRecordLoopDetectedReturns3005() {
        RuleEngineImpl e = new RuleEngineImpl();
        e.installRuleSet(new et.elisa.dra.core.cfg.RuleSet(1, "dra1.elisa.lab",
                java.util.Set.of("epc.lab"),
                List.of(new Rule("fwd", 100, Matcher.Always.TRUE,
                        new Action.Forward("pool", null, ThMode.OFF, false, List.of()))),
                Map.of("pool", new et.elisa.dra.core.cfg.RuleSet.GroupSpec("pool",
                        et.elisa.dra.core.lb.LbStrategy.RR, List.of(), true, 1))));
        e.updateCandidates("pool", List.of(
                new et.elisa.dra.core.lb.PeerHandle("hss-a", 50, 0, null)));
        DiaMsg looped = Fixtures.ulr("452041234567890", "mme-1", "epc.lab", "epc.lab",
                List.of(Fixtures.routeRecord("dra1.elisa.lab")));
        RouteDecision d = e.resolve(e.contextFor("hss-a", looped));
        assertEquals(3005, ((RouteDecision.Reject) d).resultCode());
        assertEquals("loop-detected", ((RouteDecision.Reject) d).reason());
        assertTrue(e.counters().get("dra_route_loop_detected_total") >= 1);

        DiaMsg clean = Fixtures.ulr("452041234567891", "mme-1", "epc.lab", "epc.lab",
                List.of(Fixtures.routeRecord("other-node.example")));
        RouteDecision ok = e.resolve(e.contextFor("mme-1", clean));
        assertTrue(ok instanceof RouteDecision.Forward);
        assertEquals("hss-a", ((RouteDecision.Forward) ok).preferredPeerId());
    }

    @Test
    void forwardAppendsRouteRecordOfSelfAndCarriesChosenPeer() {
        RuleEngineImpl e = engine(List.of(new Rule("s6a", 100, new Matcher.HasApp(S6A_APP),
                new Action.Forward("pool", null, ThMode.OFF, false, List.of()))));
        e.installRuleSet(new et.elisa.dra.core.cfg.RuleSet(1, "dra1.elisa.lab",
                java.util.Set.of("epc.lab"),
                List.of(new Rule("s6a", 100, new Matcher.HasApp(S6A_APP),
                        new Action.Forward("pool", null, ThMode.OFF, false, List.of()))),
                Map.of("pool", new et.elisa.dra.core.cfg.RuleSet.GroupSpec("pool",
                        et.elisa.dra.core.lb.LbStrategy.RR, List.of(), true, 1))));
        e.updateCandidates("pool", List.of(
                new et.elisa.dra.core.lb.PeerHandle("hss-a", 50, 0, null),
                new et.elisa.dra.core.lb.PeerHandle("hss-b", 50, 0, null)));
        var d = (RouteDecision.Forward) e.resolve(e.contextFor("mme-1",
                Fixtures.ulr("452049999999999", "mme-1", "epc.lab", "epc.lab", null)));
        assertTrue(d.ops().stream().anyMatch(op -> op instanceof AvpOp.AppendRouteRecord arr
                && arr.host().equals("dra1.elisa.lab")));
        assertTrue(d.failoverEnabled());
        assertEquals(ThMode.OFF, d.th());
        assertEquals("hss-a", d.preferredPeerId());
    }
}
