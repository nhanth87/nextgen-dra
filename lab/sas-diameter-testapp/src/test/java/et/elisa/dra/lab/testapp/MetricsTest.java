package et.elisa.dra.lab.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MetricsTest {

    private static final List<String> EXPECTED_KEYS = List.of(
            "heapUsed", "heapMax", "threadCount", "deadlockCount",
            "requestsTotal", "answersTotal", "errorsTotal", "lastMessageAgeMillis");

    @Test
    void snapshotExposesAllOracleKeys() {
        Map<String, Object> snapshot = new Metrics(new MessageLog()).snapshot();
        assertEquals(EXPECTED_KEYS, List.copyOf(snapshot.keySet()));
    }

    @Test
    void jvmValuesAreSane() {
        Map<String, Object> snapshot = new Metrics(new MessageLog()).snapshot();
        assertTrue(((Number) snapshot.get("heapMax")).longValue() > 0);
        assertTrue(((Number) snapshot.get("heapUsed")).longValue() >= 0);
        assertTrue(((Number) snapshot.get("threadCount")).longValue() > 0);
        assertTrue(((Number) snapshot.get("deadlockCount")).longValue() >= 0);
    }

    @Test
    void countersReflectLogActivity() {
        MessageLog log = new MessageLog();
        log.add(new MessageLog.Entry(Instant.now(), "req", "ULR", "s1", "-", "user=a"));
        log.add(new MessageLog.Entry(Instant.now(), "ans", "ULA", "s1", "2001", "ok"));
        log.add(new MessageLog.Entry(Instant.now(), "ans", "ULA", "s2", "5421", "detached"));
        Map<String, Object> snapshot = new Metrics(log).snapshot();
        assertEquals(1L, ((Number) snapshot.get("requestsTotal")).longValue());
        assertEquals(1L, ((Number) snapshot.get("answersTotal")).longValue());
        assertEquals(1L, ((Number) snapshot.get("errorsTotal")).longValue());
        assertTrue(((Number) snapshot.get("lastMessageAgeMillis")).longValue() >= 0);
    }

    @Test
    void jsonContainsEveryKey() {
        String json = new Metrics(new MessageLog()).toJson();
        for (String key : EXPECTED_KEYS) {
            assertTrue(json.contains("\"" + key + "\":"), "missing " + key + " in " + json);
        }
    }
}
