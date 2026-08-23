package et.elisa.dra.core.cfg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSetHolderTest {

    private static final String VALID_V1 = """
            {"version":1,
             "self":{"originHost":"dra1.elisa.lab","realms":["epc.lab"]},
             "peerGroups":{"pool":{"lb":"WEIGHTED_RR",
               "peers":[{"id":"hss-a","weight":70},{"id":"hss-b","weight":30}],
               "failover":{"enabled":true,"maxRetries":1}}},
             "rules":[{"name":"s6a","priority":100,"when":{"app":16777251},
               "then":{"forward":{"group":"pool","sticky":{"key":"IMSI","ttlSecs":3600}}}}]}
            """;

    private static final String VALID_V2_SAME_GROUP_SHAPE = """
            {"version":2,
             "self":{"originHost":"dra1.elisa.lab","realms":["epc.lab"]},
             "peerGroups":{"pool":{"lb":"WEIGHTED_RR",
               "peers":[{"id":"hss-a","weight":70},{"id":"hss-b","weight":30}],
               "failover":{"enabled":true,"maxRetries":1}}},
             "rules":[{"name":"s6a-v2","priority":50,"when":{"always":true},
               "then":{"forward":{"group":"pool"}}}]}
            """;

    @Test
    void applyValidSwapsAtomicallyAndNotifiesSink() {
        List<RuleSet> installed = new ArrayList<>();
        RuleSetHolder h = new RuleSetHolder(installed::add);
        assertTrue(h.applyCandidate(VALID_V1).isEmpty());
        assertEquals(1, h.version());
        assertEquals("pool", h.runtime().groups().get("pool").name());
        assertEquals(et.elisa.dra.core.lb.LbStrategy.WEIGHTED_RR,
                h.runtime().groups().get("pool").strategy());
        assertEquals("dra1.elisa.lab", h.runtime().selfOriginHost());
        assertEquals(1, installed.size());

        assertTrue(h.applyCandidate(VALID_V2_SAME_GROUP_SHAPE).isEmpty());
        assertEquals(2, h.version());
        assertEquals(2, installed.size());
        assertEquals("s6a-v2", h.runtime().rules().get(0).name());
        assertTrue(h.currentJson().contains("\"version\":2"));
    }

    @Test
    void rulesSortedByPriorityAscending() {
        RuleSetHolder h = new RuleSetHolder();
        assertTrue(h.applyCandidate("""
                {"version":5,"self":{"originHost":"d","realms":["r"]},
                 "peerGroups":{},
                 "rules":[
                   {"name":"c","priority":300,"when":{"always":true},
                    "then":{"reject":{"resultCode":3002,"reason":"c"}}},
                   {"name":"a","priority":100,"when":{"always":true},
                    "then":{"reject":{"resultCode":3002,"reason":"a"}}},
                   {"name":"b","priority":200,"when":{"always":true},
                    "then":{"reject":{"resultCode":3002,"reason":"b"}}}
                 ]}
                """).isEmpty());
        assertEquals(List.of("a", "b", "c"),
                h.runtime().rules().stream()
                        .map(r -> ((et.elisa.dra.core.engine.Action.Reject) r.then()).reason())
                        .toList());
    }

    @Test
    void invalidJsonKeepsLastGood() {
        RuleSetHolder h = new RuleSetHolder();
        assertTrue(h.applyCandidate(VALID_V1).isEmpty());
        String before = h.currentJson();
        List<String> errors = h.applyCandidate("{not json at all");
        assertTrue(!errors.isEmpty());
        assertTrue(errors.get(0).startsWith("json parse error"));
        assertEquals(before, h.currentJson());
        assertEquals(1, h.version());

        List<String> unknownKey = h.applyCandidate("""
                {"version":9,"self":{"originHost":"d","realms":["r"]},"wat":true}
                """);
        assertTrue(!unknownKey.isEmpty());
        assertEquals(before, h.currentJson());
        assertEquals(1, h.version());
    }

    @Test
    void versionMustIncreaseMonotonically() {
        RuleSetHolder h = new RuleSetHolder();
        assertTrue(h.applyCandidate(VALID_V1).isEmpty());
        List<String> errors = h.applyCandidate("""
                {"version":1,"self":{"originHost":"d","realms":["r"]},
                 "peerGroups":{},"rules":[]}
                """);
        assertTrue(errors.stream().anyMatch(s -> s.contains("version must increase")));
        List<String> lower = h.applyCandidate(VALID_V2_SAME_GROUP_SHAPE.replace(
                "\"version\":2", "\"version\":0"));
        assertTrue(lower.stream().anyMatch(s -> s.contains("version must increase")));
        assertEquals(1, h.version());
    }

    @Test
    void orphanGroupReferenceRejected() {
        RuleSetHolder h = new RuleSetHolder();
        List<String> errors = h.applyCandidate("""
                {"version":1,"self":{"originHost":"d","realms":["r"]},
                 "peerGroups":{"g1":{"lb":"RR","peers":[{"id":"p","weight":1}]}},
                 "rules":[{"name":"n","priority":10,"when":{"always":true},
                   "then":{"forward":{"group":"ghost-pool"}}}]}
                """);
        assertTrue(errors.stream().anyMatch(s -> s.contains("undefined group 'ghost-pool'")));
        assertEquals(0, h.version());
    }

    @Test
    void emptyGroupAndBadLbRejected() {
        RuleSetHolder h = new RuleSetHolder();
        List<String> errors = h.applyCandidate("""
                {"version":1,"self":{"originHost":"d","realms":["r"]},
                 "peerGroups":{"empty":{"lb":"RR","peers":[]},
                               "badlb":{"lb":"RANDOM","peers":[{"id":"p","weight":1}]}},
                 "rules":[{"name":"n","priority":10,"when":{"always":true},
                   "then":{"forward":{"group":"empty"}}}]}
                """);
        assertTrue(errors.stream().anyMatch(s -> s.contains("'empty' needs at least one peer")));
        assertTrue(errors.stream().anyMatch(s -> s.contains("unknown lb strategy 'RANDOM'")));
    }

    @Test
    void selfRealmLoopWithThOffRejectedButThOnAllowed() {
        RuleSetHolder h = new RuleSetHolder();
        List<String> loopErrors = h.applyCandidate("""
                {"version":1,"self":{"originHost":"dra1","realms":["epc.mnc01.lab"]},
                 "peerGroups":{"edge":{"lb":"RR","peers":[{"id":"x","weight":1}]}},
                 "rules":[{"name":"loop","priority":10,
                   "when":{"and":[{"realm":{"field":"DEST","op":"EQ",
                     "value":"epc.mnc01.lab"}},{"cmd":[316]}]},
                   "then":{"forward":{"group":"edge"}}}]}
                """);
        assertTrue(loopErrors.stream().anyMatch(s -> s.contains("configuration loop")));

        RuleSetHolder okTh = new RuleSetHolder();
        assertTrue(okTh.applyCandidate("""
                {"version":1,"self":{"originHost":"dra1","realms":["epc.mnc01.lab"]},
                 "peerGroups":{"edge":{"lb":"RR","peers":[{"id":"x","weight":1}]}},
                 "rules":[{"name":"th-edge","priority":10,
                   "when":{"and":[{"realm":{"field":"DEST","op":"EQ",
                     "value":"epc.mnc01.lab"}},{"cmd":[316]}]},
                   "then":{"forward":{"group":"edge","th":"FULL_EDGE"}}}]}
                """).isEmpty());
    }

    @Test
    void badAppIdAndStickyKeyRejected() {
        RuleSetHolder h = new RuleSetHolder();
        List<String> errors = h.applyCandidate("""
                {"version":1,"self":{"originHost":"d","realms":["r"]},
                 "peerGroups":{"g":{"lb":"RR","peers":[{"id":"p","weight":1}]}},
                 "rules":[{"name":"bad-app","priority":10,
                   "when":{"and":[{"app":16777251},{"app":-5}]},
                   "then":{"forward":{"group":"g","sticky":{"key":"NOT_A_KEY","ttlSecs":60}}}}]}
                """);
        assertTrue(errors.stream().anyMatch(s -> s.contains("invalid application id -5")));
        assertTrue(errors.stream().anyMatch(s -> s.contains("not an extractable key")));
    }

    @Test
    void holderWiredToEngineReconcilesGroupsPreservingCandidates() {
        var engine = new et.elisa.dra.core.engine.RuleEngineImpl();
        RuleSetHolder h = new RuleSetHolder(engine::installRuleSet);
        assertTrue(h.applyCandidate(VALID_V1).isEmpty());
        engine.updateCandidates("pool", List.of(
                new et.elisa.dra.core.lb.PeerHandle("hss-a", 70, 0, null),
                new et.elisa.dra.core.lb.PeerHandle("hss-b", 30, 0, null)));

        assertTrue(h.applyCandidate(VALID_V2_SAME_GROUP_SHAPE.replace(
                "\"version\":2", "\"version\":7")).isEmpty());
        assertEquals(7, h.version());
        assertTrue(engine.groupIds().contains("pool"));
        assertEquals(2, engine.group("pool").candidates().size());

        var ctx = engine.contextFor("mme-1",
                new et.elisa.dra.core.wire.DiaMsg(1, 128, 316, 16777251, 1, 1, "s",
                        "mme-1", "epc.lab", null, "hss.epc.lab", 0, java.util.List.of()));
        var d = (et.elisa.dra.core.engine.RouteDecision.Forward) engine.resolve(ctx);
        assertEquals("hss-a", d.preferredPeerId());
    }
}
