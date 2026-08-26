package et.elisa.dra.app.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import et.elisa.dra.app.admin.RulesResource;
import et.elisa.dra.core.cfg.RuleSetHolder;
import jakarta.ws.rs.core.Response;

class RulesPersistenceWiringTest {

    private static final String VALID_V1 = """
            {"version":1,
             "self":{"originHost":"dra1.elisa.lab","realms":["epc.lab"]},
             "peerGroups":{"pool":{"lb":"RR","peers":[{"id":"hss-a","weight":1}],
               "failover":{"enabled":false,"maxRetries":0}}},
             "rules":[{"name":"s6a","priority":100,"when":{"app":16777251},
               "then":{"forward":{"group":"pool"}}}]}
            """;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entity(Response r) {
        return (Map<String, Object>) r.getEntity();
    }

    @Test
    void successfulApplyPersistsAndAudits() {
        RuleSetHolder holder = new RuleSetHolder();
        List<int[]> versions = new ArrayList<>();
        List<String> payloads = new ArrayList<>();
        RouteConfigSink sink = new RouteConfigSink() {
            @Override
            public void persistApplied(int version, String json) {
                versions.add(new int[]{version});
                payloads.add(json);
            }

            @Override
            public Optional<String> loadLatest() {
                return Optional.empty();
            }
        };
        RulesResource res = new RulesResource(holder, sink, null);
        Response ok = res.apply(VALID_V1);
        assertEquals(Boolean.TRUE, entity(ok).get("applied"));
        assertEquals(Boolean.TRUE, entity(ok).get("persisted"));
        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0)[0]);
        assertTrue(payloads.get(0).contains("\"version\":1"));
    }

    @Test
    void memoryOnlySetupReportsPersistedFalse() {
        RulesResource res = new RulesResource(new RuleSetHolder());
        Response ok = res.apply(VALID_V1);
        assertEquals(Boolean.TRUE, entity(ok).get("applied"));
        assertEquals(Boolean.FALSE, entity(ok).get("persisted"));
    }

    @Test
    void bootLoaderRestoresDurablePayloadBeforeSeedFile() {
        RuleSetHolder holder = new RuleSetHolder();
        RouteConfigSink sink = new RouteConfigSink() {
            @Override
            public void persistApplied(int version, String json) {
            }

            @Override
            public Optional<String> loadLatest() {
                return Optional.of(VALID_V1.replace("\"name\":\"s6a\"", "\"name\":\"from-db\""));
            }
        };
        RulesBootLoader loader = new RulesBootLoader(holder, sink);
        loader.onStartup(null);
        assertTrue(holder.currentJson().contains("from-db"),
                "durable payload must win over any file seed");
        assertEquals(1, holder.version());
    }

    @Test
    void bootLoaderWithoutSinkStartsEmptyQuietly() {
        RuleSetHolder holder = new RuleSetHolder();
        RulesBootLoader loader = new RulesBootLoader(holder, (RouteConfigSink) null);
        loader.onStartup(null);
        // no seed file next to surefire CWD -> engine stays empty, no throw
        assertEquals(0, holder.version());
    }
}
