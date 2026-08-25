package et.elisa.dra.core.engine;

import et.elisa.dra.core.bind.BindingEntry;
import et.elisa.dra.core.cfg.RuleSet;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static et.elisa.dra.core.engine.Fixtures.S6A_APP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineStickyTest {

    private static final String IMSI = "452040123456789";

    private RuleEngineImpl engine(Map<String, BindingEntry> bindings,
                                  List<String> auditSink,
                                  String forwardGroup) {
        RuleEngineImpl e = new RuleEngineImpl(new KeyExtractorImpl(),
                key -> java.util.Optional.ofNullable(bindings.get(key)),
                (p, app) -> true,
                Clock.systemUTC(),
                auditSink::add);
        e.installRuleSet(new RuleSet(1, "dra1.elisa.lab", java.util.Set.of("epc.lab"),
                List.of(new Rule("s6a-sticky", 100, new Matcher.HasApp(S6A_APP),
                        new Action.Forward(forwardGroup, new StickyBinding("IMSI", 86400),
                                ThMode.OFF, false, List.of()))),
                Map.of(
                        "g1", new RuleSet.GroupSpec("g1",
                                et.elisa.dra.core.lb.LbStrategy.RR, List.of(), true, 1),
                        "g2", new RuleSet.GroupSpec("g2",
                                et.elisa.dra.core.lb.LbStrategy.RR, List.of(), true, 1))));
        e.updateCandidates("g1", List.of(
                new et.elisa.dra.core.lb.PeerHandle("hss-a", 50, 0, null),
                new et.elisa.dra.core.lb.PeerHandle("hss-b", 50, 0, null)));
        e.updateCandidates("g2", List.of(
                new et.elisa.dra.core.lb.PeerHandle("ipx-a", 50, 0, null)));
        return e;
    }

    @Test
    void stickyMissPicksViaLbAndCarriesStickyForCapture() {
        RuleEngineImpl e = engine(new HashMap<>(), new java.util.ArrayList<>(), "g1");
        var d = (RouteDecision.Forward) e.resolve(e.contextFor("mme-1",
                Fixtures.ulr(IMSI, "mme-1", "epc.lab", "epc.lab", null)));
        assertEquals("g1", d.group());
        assertEquals("hss-a", d.preferredPeerId());
        assertEquals("IMSI:" + IMSI, d.sticky().key());
        assertEquals(86400L, d.sticky().ttlSeconds());
        assertTrue(e.counters().get("dra_sticky_rebind_total") == 0);
    }

    @Test
    void stickyHitSameGroupPrefersBoundPeer() {
        HashMap<String, BindingEntry> bindings = new HashMap<>();
        bindings.put("IMSI:" + IMSI, new BindingEntry("IMSI:" + IMSI, "g1", "hss-b",
                "mme-1.epc.lab", "epc.lab", "mme-1", Instant.now(),
                Instant.now().plusSeconds(3600)));
        RuleEngineImpl e = engine(bindings, new java.util.ArrayList<>(), "g1");
        for (int i = 0; i < 5; i++) {
            var d = (RouteDecision.Forward) e.resolve(e.contextFor("mme-1",
                    Fixtures.ulr(IMSI, "mme-1", "epc.lab", "epc.lab", null)));
            assertEquals("hss-b", d.preferredPeerId());
        }
    }

    @Test
    void stickyHitDifferentGroupRebindsWithAudit() {
        HashMap<String, BindingEntry> bindings = new HashMap<>();
        bindings.put("IMSI:" + IMSI, new BindingEntry("IMSI:" + IMSI, "g2", "ipx-a",
                "mme-1.epc.lab", "epc.lab", "mme-1", Instant.now(),
                Instant.now().plusSeconds(3600)));
        java.util.ArrayList<String> audit = new java.util.ArrayList<>();
        RuleEngineImpl e = engine(bindings, audit, "g1");
        var d = (RouteDecision.Forward) e.resolve(e.contextFor("mme-1",
                Fixtures.ulr(IMSI, "mme-1", "epc.lab", "epc.lab", null)));
        assertEquals("g1", d.group());
        assertTrue(!"ipx-a".equals(d.preferredPeerId()));
        assertTrue(audit.stream().anyMatch(s -> s.contains("rebinding") && s.contains("g2")));
        assertTrue(e.counters().get("dra_sticky_rebind_total") >= 1);
    }

    @Test
    void stickyKeyAbsentInMessageSkipsLookup() {
        RuleEngineImpl e = engine(new HashMap<>(), new java.util.ArrayList<>(), "g1");
        DiaMsg noImsi = Fixtures.ulr(null, "mme-1", "epc.lab", "epc.lab", null);
        var d = (RouteDecision.Forward) e.resolve(e.contextFor("mme-1", noImsi));
        assertEquals("hss-a", d.preferredPeerId());
        assertTrue(e.counters().get("dra_sticky_rebind_total") == 0);
    }
}
