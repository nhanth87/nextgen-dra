package et.elisa.dra.core.engine;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static et.elisa.dra.core.engine.Fixtures.S6A_APP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineLoopHairpinEligibilityTest {

    private static final class SettableClock extends Clock {

        private Instant now;

        SettableClock(Instant now) {
            this.now = now;
        }

        void set(Instant t) {
            this.now = t;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private Rule forward(String group, boolean allowHairpin) {
        return new Rule("fwd", 100, new Matcher.HasApp(S6A_APP),
                new Action.Forward(group, null, ThMode.OFF, allowHairpin, List.of()));
    }

    private RuleEngineImpl engine(java.util.function.Predicate<String> eligibleIds,
                                  boolean allowHairpin,
                                  List<Rule> rules) {
        RuleEngineImpl e = new RuleEngineImpl(new KeyExtractorImpl(), StickyLookup.empty(),
                (p, app) -> eligibleIds.test(p.peerId()),
                new SettableClock(Instant.parse("2026-08-23T00:00:00Z")), s -> {
        });
        e.installRuleSet(new et.elisa.dra.core.cfg.RuleSet(1, "dra1.elisa.lab",
                java.util.Set.of("epc.lab"), rules,
                Map.of("pool", new et.elisa.dra.core.cfg.RuleSet.GroupSpec("pool",
                        et.elisa.dra.core.lb.LbStrategy.RR, List.of(), true, 1))));
        e.updateCandidates("pool", List.of(
                new et.elisa.dra.core.lb.PeerHandle("hss-a", 50, 0, null),
                new et.elisa.dra.core.lb.PeerHandle("hss-b", 50, 0, null)));
        return e;
    }

    @Test
    void antiHairpinSkipsIngressPeerUnlessAllowed() {
        RuleEngineImpl strict = engine(p -> true, false, List.of(forward("pool", false)));
        var d = (RouteDecision.Forward) strict.resolve(strict.contextFor("hss-a",
                Fixtures.ulr("452040000000001", "mme-1", "epc.lab", "epc.lab", null)));
        assertEquals("hss-b", d.preferredPeerId());

        RuleEngineImpl lenient = engine(p -> true, true, List.of(forward("pool", true)));
        var d2 = (RouteDecision.Forward) lenient.resolve(lenient.contextFor("hss-a",
                Fixtures.ulr("452040000000002", "mme-1", "epc.lab", "epc.lab", null)));
        assertEquals("hss-a", d2.preferredPeerId());
    }

    @Test
    void eligibilityFiltersPeersAndFallsClosedWhenNoneLeft() {
        RuleEngineImpl e = engine(p -> p.equals("hss-b"), false, List.of(forward("pool", false)));
        for (int i = 0; i < 6; i++) {
            var d = (RouteDecision.Forward) e.resolve(e.contextFor("mme-1",
                    Fixtures.ulr("45204111111111" + i, "mme-1", "epc.lab", "epc.lab", null)));
            assertEquals("hss-b", d.preferredPeerId());
        }
        assertTrue(e.counters().get("dra_route_forward_total") >= 6);

        RuleEngineImpl none = engine(p -> false, false, List.of(forward("pool", false)));
        RouteDecision rejected = none.resolve(none.contextFor("mme-1",
                Fixtures.ulr("452042222222223", "mme-1", "epc.lab", "epc.lab", null)));
        assertEquals(et.elisa.dra.core.common.DraResultCodes.UNABLE_TO_DELIVER,
                ((RouteDecision.Reject) rejected).resultCode());
        assertTrue(none.counters().get("dra_route_no_candidate_total") >= 1);
    }

    @Test
    void redirectActionCachesByRealmAndAppWithTtlExpiry() {
        SettableClock clock = new SettableClock(Instant.parse("2026-08-23T00:00:00Z"));
        RuleEngineImpl e = new RuleEngineImpl(new KeyExtractorImpl(), StickyLookup.empty(),
                (p, app) -> true, clock, s -> {
        });
        e.installRuleSet(new et.elisa.dra.core.cfg.RuleSet(1, "dra1.elisa.lab",
                java.util.Set.of("epc.lab"),
                List.of(new Rule("redir", 100, Matcher.Always.TRUE,
                        new Action.Redirect("hss-pool.epc.lab", "epc.lab", 5))),
                Map.of()));
        RoutingContext ctx = e.contextFor("mme-1",
                Fixtures.ulr("452043333333331", "mme-1", "epc.lab", "epc.lab", null));
        RouteDecision d = e.resolve(ctx);
        assertTrue(d instanceof RouteDecision.Redirect);
        assertEquals("hss-pool.epc.lab", ((RouteDecision.Redirect) d).host());
        assertEquals(5L, ((RouteDecision.Redirect) d).cacheSeconds());
        assertTrue(e.redirectCacheActive("epc.lab", S6A_APP));
        assertFalse(e.redirectCacheActive("other-realm", S6A_APP));
        clock.set(Instant.parse("2026-08-23T00:00:03Z"));
        assertTrue(e.redirectCacheActive("epc.lab", S6A_APP));
        clock.set(Instant.parse("2026-08-23T00:00:06Z"));
        assertFalse(e.redirectCacheActive("epc.lab", S6A_APP));
        assertTrue(e.counters().get("dra_route_redirect_total") == 1);
    }

    @Test
    void unknownGroupFallsClosed() {
        RuleEngineImpl e = engine(p -> true, false, List.of(forward("ghost", false)));
        RouteDecision d = e.resolve(e.contextFor("mme-1",
                Fixtures.ulr("452044444444444", "mme-1", "epc.lab", "epc.lab", null)));
        assertEquals(et.elisa.dra.core.common.DraResultCodes.UNABLE_TO_DELIVER,
                ((RouteDecision.Reject) d).resultCode());
    }
}
