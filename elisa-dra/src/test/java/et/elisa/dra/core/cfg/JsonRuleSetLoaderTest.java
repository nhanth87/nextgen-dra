package et.elisa.dra.core.cfg;

import et.elisa.dra.core.engine.Action;
import et.elisa.dra.core.engine.Matcher;
import et.elisa.dra.core.engine.RoutingContext;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRuleSetLoaderTest {

    private final JsonRuleSetLoader loader = new JsonRuleSetLoader();

    private static final String DOC_EXAMPLE = """
            {
              "version": 7,
              "self": {
                "originHost": "dra1.elisa.lab",
                "realms": ["epc.mnc01.mcc452.3gppnetwork.org",
                           "ims.mnc01.mcc452.3gppnetwork.org"]
              },
              "peerGroups": {
                "mvno-hss-pool": {
                  "lb": "WEIGHTED_RR",
                  "peers": [
                    { "id": "hss-a", "weight": 70 },
                    { "id": "hss-b", "weight": 30 }
                  ],
                  "failover": { "enabled": true, "maxRetries": 1 }
                }
              },
              "rules": [
                {
                  "name": "s6a-mvno-hss",
                  "priority": 100,
                  "when": {
                    "and": [
                      { "app": 16777251 },
                      { "avp": { "path": "User-Name", "op": "PREFIX", "value": "4520402" } }
                    ]
                  },
                  "then": {
                    "forward": {
                      "group": "mvno-hss-pool",
                      "sticky": { "key": "IMSI", "ttlSecs": 86400 }
                    }
                  }
                },
                {
                  "name": "roaming-out-vplmn-edge",
                  "priority": 200,
                  "when": {
                    "and": [
                      { "app": 16777251 },
                      { "plmnFrom": "IMSI", "notIn": ["45201", "45204"] }
                    ]
                  },
                  "then": {
                    "forward": {
                      "group": "ipx-edge",
                      "th": "PSEUDO_HOST_DETERMINISTIC"
                    }
                  }
                },
                {
                  "name": "default-drop-unknown",
                  "priority": 65000,
                  "when": { "always": true },
                  "then": { "reject": { "resultCode": 3002, "reason": "no-route" } }
                }
              ]
            }
            """;

    @Test
    void parsesDocExampleShapeExactly() throws Exception {
        RuleSetFile f = loader.parse(DOC_EXAMPLE);
        assertEquals(7, f.version());
        assertEquals("dra1.elisa.lab", f.self().originHost());
        assertEquals(2, f.self().realms().size());
        assertEquals(1, f.peerGroups().size());
        var g = f.peerGroups().get("mvno-hss-pool");
        assertEquals("WEIGHTED_RR", g.lb());
        assertEquals(70, g.peers().get(0).weight());
        assertEquals(Boolean.TRUE, g.failover().enabled());
        assertEquals(1, g.failover().maxRetries());
        assertEquals(3, f.rules().size());
        assertEquals("s6a-mvno-hss", f.rules().get(0).name());

        Matcher.And when = (Matcher.And) f.rules().get(0).when().matcher();
        Matcher.HasApp app = (Matcher.HasApp) when.parts().get(0);
        assertEquals(16777251, app.appId());
        Matcher.AvpMatch avp = (Matcher.AvpMatch) when.parts().get(1);
        assertEquals("IMSI", avp.path());
        assertEquals(Matcher.AvpMatch.Op.PREFIX, avp.op());
        assertEquals("4520402", avp.value());

        Action.Forward fwd = (Action.Forward) f.rules().get(0).then().action();
        assertEquals("mvno-hss-pool", fwd.group());
        assertEquals("IMSI", fwd.sticky().key());
        assertEquals(86400L, fwd.sticky().ttlSeconds());

        Action.Forward thFwd = (Action.Forward) f.rules().get(1).then().action();
        assertEquals(et.elisa.dra.core.engine.ThMode.PSEUDO_HOST_DETERMINISTIC, thFwd.th());

        Action.Reject rej = (Action.Reject) f.rules().get(2).then().action();
        assertEquals(3002, rej.resultCode());
        assertEquals("no-route", rej.reason());
    }

    @Test
    void serializesBackToSameShape() throws Exception {
        RuleSetFile f = loader.parse(DOC_EXAMPLE);
        String json = loader.toJson(f);
        RuleSetFile again = loader.parse(json);
        assertEquals(f.version(), again.version());
        assertEquals(f.rules().size(), again.rules().size());
        var g0 = again.peerGroups().get("mvno-hss-pool");
        assertEquals(70, g0.peers().get(0).weight());
        var r0when = (Matcher.And) again.rules().get(0).when().matcher();
        var avp = (Matcher.AvpMatch) r0when.parts().get(1);
        assertEquals("IMSI", avp.path());
        assertEquals("4520402", avp.value());
        assertTrue(json.contains("\"plmnFrom\""));
        assertTrue(json.contains("\"notIn\""));
        assertTrue(json.contains("\"always\""));
    }

    @Test
    void rejectsUnknownMatcherAndActionKeys() {
        String badMatcher = """
                {"version":1,"self":{"originHost":"d","realms":["r"]},
                 "peerGroups":{"g":{"lb":"RR","peers":[{"id":"p","weight":1}]}},
                 "rules":[{"name":"n","priority":1,"when":{"wat":1},
                 "then":{"reject":{"resultCode":3002,"reason":"x"}}}]}
                """;
        assertThrows(Exception.class, () -> loader.parse(badMatcher));
        String badAction = """
                {"version":1,"self":{"originHost":"d","realms":["r"]},
                 "peerGroups":{"g":{"lb":"RR","peers":[{"id":"p","weight":1}]}},
                 "rules":[{"name":"n","priority":1,"when":{"always":true},
                 "then":{"explode":{}}}]}
                """;
        assertThrows(Exception.class, () -> loader.parse(badAction));
        String malformedJson = "{version:";
        assertThrows(Exception.class, () -> loader.parse(malformedJson));
    }

    @Test
    void parsesAllMatcherVariantsAndEvaluates() throws Exception {
        String cfg = """
                {"version":1,"self":{"originHost":"dra1","realms":["epc.lab"]},
                 "peerGroups":{"g":{"lb":"RR","peers":[{"id":"p","weight":1}]}},
                 "rules":[
                  {"name":"realm-host-cmd-flag-drmp-ingress","priority":10,"when":{
                     "and":[
                       {"realm":{"field":"DEST","op":"SUFFIX","value":".epc.lab"}},
                       {"host":{"field":"ORIG","op":"EQ","value":"mme-1.epc.lab"}},
                       {"cmd":[316]},
                       {"flag":"R"},
                       {"drmpAtLeast":4},
                       {"ingressPeerIn":["link-a"]}
                     ]},
                   "then":{"reject":{"resultCode":3003,"reason":"all"}}},
                  {"name":"cidr-and-list-and-regex","priority":20,"when":{
                     "or":[
                       {"avp":{"path":"FRAMED_IP","op":"IP_IN_CIDR","value":"10.64.0.0/12"}},
                       {"avp":{"path":"APN","op":"IN_LIST","value":"ims,gpdu"}},
                       {"avp":{"path":"SESSION_ID","op":"CONTAINS","value":"special"}},
                       {"realm":{"field":"ORIG","op":"REGEX","value":"[a-z]+\\\\.mnc01\\\\..*"}}
                     ]},
                   "then":{"redirect":{"host":"pool.example","cacheSecs":30}}}
                 ]}
                """;
        RuleSetFile f = loader.parse(cfg);
        assertEquals(2, f.rules().size());
        Matcher m1 = f.rules().get(0).when().matcher();

        RoutingContext ctx = ctxFor(Map.of(), "link-a", "mme-1.epc.lab", "epc.lab",
                "hss.epc.lab", 5);
        assertTrue(m1.evaluate(ctx));

        RoutingContext wrongLink = ctxFor(Map.of(), "link-b", "mme-1.epc.lab", "epc.lab",
                "hss.epc.lab", 5);
        assertTrue(!m1.evaluate(wrongLink));

        Matcher orTree = f.rules().get(1).when().matcher();
        assertTrue(orTree.evaluate(ctxFor(Map.of("FRAMED_IP", "10.70.1.2"), "l", "o", "r",
                "d", 10)));
        assertTrue(orTree.evaluate(ctxFor(Map.of("APN", "ims"), "l", "o", "r", "d", 10)));
        assertFalse(orTree.evaluate(ctxFor(Map.of("APN", "internet"), "l", "o", "r", "d", 10)));
        assertTrue(orTree.evaluate(ctxFor(Map.of("SESSION_ID", "x-special-y"), "l", "o", "r",
                "d", 10)));
        assertTrue(orTree.evaluate(ctxFor(Map.of(), "l", "mme-1", "abc.mnc01.pub",
                "d", 10)));
        assertFalse(orTree.evaluate(ctxFor(Map.of(), "l", "mme-1", "other.realm", "d", 10)));

        Action.Redirect rd = (Action.Redirect) f.rules().get(1).then().action();
        assertEquals("pool.example", rd.host());
        assertEquals(30L, rd.cacheSeconds());
    }

    private static RoutingContext ctxFor(Map<String, String> keys, String ingress,
                                         String origHost, String origRealm,
                                         String destRealm, int drmp) {
        DiaMsg msg = new DiaMsg(1, 128, 316, 16777251, 1, 1, "s", origHost, origRealm,
                null, destRealm, 0, List.of());
        return new RoutingContext(ingress, msg.applicationId(), msg.commandCode(),
                true, false, 0, 0, drmp, null, destRealm, origHost, origRealm,
                Map.copyOf(keys));
    }

    @Test
    void plmnMatchersEvaluateFromBothSources() throws Exception {
        String cfg = """
                {"version":1,"self":{"originHost":"d","realms":["r"]},
                 "peerGroups":{"g":{"lb":"RR","peers":[{"id":"p","weight":1}]}},
                 "rules":[
                  {"name":"in-set","priority":10,"when":{"plmnFrom":"VISITED_PLMN",
                    "in":["45201","45204"]},
                   "then":{"reject":{"resultCode":3002,"reason":"home"}}},
                  {"name":"not-in-set","priority":20,"when":{"plmnFrom":"IMSI",
                    "notIn":["45201","45204"]},
                   "then":{"reject":{"resultCode":3002,"reason":"roam"}}}
                 ]}
                """;
        RuleSetFile f = loader.parse(cfg);
        Matcher inSet = f.rules().get(0).when().matcher();
        Matcher notInSet = f.rules().get(1).when().matcher();

        RoutingContext viettel = ctxFor(Map.of("VISITED_PLMN", "45201", "IMSI",
                "452010000000001"), "l", "o", "r", "d", 10);
        assertTrue(inSet.evaluate(viettel));
        assertTrue(!notInSet.evaluate(viettel));

        RoutingContext foreign = ctxFor(Map.of("VISITED_PLMN", "26202", "IMSI",
                "262020000000002"), "l", "o", "r", "d", 10);
        assertTrue(notInSet.evaluate(foreign));
        assertTrue(!inSet.evaluate(foreign));
    }
}
