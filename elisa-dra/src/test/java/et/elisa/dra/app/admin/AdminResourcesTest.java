package et.elisa.dra.app.admin;

import et.elisa.dra.core.peer.PeerHealth;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminResourcesTest {

    private static final String VALID_V1 = """
            {"version":1,
             "self":{"originHost":"dra1.elisa.lab","realms":["epc.lab"]},
             "peerGroups":{"pool":{"lb":"WEIGHTED_RR",
               "peers":[{"id":"hss-a","weight":70},{"id":"hss-b","weight":30}],
               "failover":{"enabled":true,"maxRetries":1}}},
             "rules":[{"name":"s6a","priority":100,"when":{"app":16777251},
               "then":{"forward":{"group":"pool"}}}]}
            """;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entity(Response r) {
        return (Map<String, Object>) r.getEntity();
    }

    @Test
    void peersResourceExposesHealthWithLiveFlag() {
        Map<String, PeerHealth> health = new LinkedHashMap<>();
        health.put("hss-a", new PeerHealth("hss-a", true, true, true, 12,
                Set.of(16777251), "OPEN"));
        health.put("hss-b", new PeerHealth("hss-b", true, false, false, 0,
                Set.of(), "WAITING"));
        AtomicLong bindings = new AtomicLong(42);
        List<String> ops = new java.util.ArrayList<>();
        AdminPort port = new AdminPort() {
            @Override
            public boolean live() {
                return true;
            }

            @Override
            public Map<String, PeerHealth> peersHealth() {
                return health;
            }

            @Override
            public long bindingsCount() {
                return bindings.get();
            }

            @Override
            public boolean enablePeer(String peerId) {
                ops.add("enable:" + peerId);
                return true;
            }

            @Override
            public boolean disablePeer(String peerId) {
                ops.add("disable:" + peerId);
                return true;
            }
        };
        PeersResource res = new PeersResource(port);
        Map<String, Object> view = res.peers();
        assertEquals(Boolean.TRUE, view.get("live"));
        assertEquals(health, view.get("peers"));
        assertTrue(((PeerHealth) ((Map<String, PeerHealth>) view.get("peers")).get("hss-a")).ready());
        assertFalse(((PeerHealth) ((Map<String, PeerHealth>) view.get("peers")).get("hss-b")).ready());

        Map<String, Object> en = res.enable("hss-a");
        assertEquals(Boolean.TRUE, en.get("applied"));
        assertEquals("enabled", en.get("targetState"));
        assertEquals(List.of("enable:hss-a"), ops);
        Map<String, Object> di = res.disable("hss-a");
        assertEquals(Boolean.TRUE, di.get("applied"));
        assertEquals("disabled", di.get("targetState"));
        assertEquals(List.of("enable:hss-a", "disable:hss-a"), ops);

        BindingsResource bindingsRes = new BindingsResource(port);
        assertEquals(42L, bindingsRes.count().get("count"));

        PeersResource noop = new PeersResource();
        assertEquals(Boolean.FALSE, noop.peers().get("live"));
        assertTrue(((Map<String, Object>) noop.peers().get("peers")).isEmpty());
    }

    @Test
    void rulesResourceAppliesValidRejectsInvalidKeepsLastGood() {
        RulesResource res = new RulesResource(new et.elisa.dra.core.cfg.RuleSetHolder());
        Response initial = res.current();
        assertEquals(200, initial.getStatus());

        Response ok = res.apply(VALID_V1);
        assertEquals(200, ok.getStatus());
        assertEquals(Boolean.TRUE, entity(ok).get("applied"));
        assertEquals(1, entity(ok).get("version"));
        String lastGood = (String) res.current().getEntity();
        assertTrue(lastGood.contains("\"version\":1"));

        Response badJson = res.apply("{definitely not json");
        assertEquals(400, badJson.getStatus());
        assertEquals(Boolean.FALSE, entity(badJson).get("applied"));
        assertFalse(((List<String>) entity(badJson).get("errors")).isEmpty());
        assertEquals(1, entity(badJson).get("lastGoodVersion"));

        Response badVersion = res.apply(VALID_V1.replace("\"version\":1", "\"version\":1"));
        assertEquals(400, badVersion.getStatus());

        Response orphan = res.apply("""
                {"version":9,"self":{"originHost":"d","realms":["r"]},
                 "peerGroups":{},
                 "rules":[{"name":"n","priority":1,"when":{"always":true},
                   "then":{"forward":{"group":"ghost"}}}]}
                """);
        assertEquals(400, orphan.getStatus());
        assertTrue(((List<String>) entity(orphan).get("errors")).stream()
                .anyMatch(s -> s.contains("ghost")));

        Response v2 = res.apply(VALID_V1
                .replace("\"version\":1", "\"version\":5")
                .replace("\"name\":\"s6a\"", "\"name\":\"s6a-v5\""));
        assertEquals(200, v2.getStatus());
        assertEquals(5, entity(v2).get("version"));
        assertTrue(((String) res.current().getEntity()).contains("s6a-v5"));
    }

    @Test
    void telemetryResourceSnapshotsCounters() {
        TelemetryPort port = new TelemetryPort() {
            @Override
            public boolean live() {
                return true;
            }

            @Override
            public Map<String, Long> snapshot() {
                Map<String, Long> m = new HashMap<>();
                m.put(et.elisa.dra.core.metrics.MetricsNames.TX_TOTAL, 1234L);
                return m;
            }
        };
        TelemetryResource res = new TelemetryResource(port);
        Map<String, Object> out = res.telemetry();
        assertEquals(Boolean.TRUE, out.get("live"));
        assertEquals(1234L, ((Map<String, Long>) out.get("counters"))
                .get(et.elisa.dra.core.metrics.MetricsNames.TX_TOTAL));

        TelemetryResource noop = new TelemetryResource();
        assertEquals(Boolean.FALSE, noop.telemetry().get("live"));
    }
}
