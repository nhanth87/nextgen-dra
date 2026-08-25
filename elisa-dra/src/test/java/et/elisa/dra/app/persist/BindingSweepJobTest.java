package et.elisa.dra.app.persist;

import et.elisa.dra.core.bind.BindingEntry;
import et.elisa.dra.core.bind.BindingMetricsBridge;
import et.elisa.dra.core.bind.InMemoryBindingStore;
import et.elisa.dra.core.bind.PersistenceHook;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingSweepJobTest {

    static class FakeSource implements SweepSource {
        final List<BindingEntry> pending = new ArrayList<>();
        Instant lastNow;
        int lastLimit = -1;

        @Override
        public List<BindingEntry> sweepExpired(Instant now, int limit) {
            lastNow = now;
            lastLimit = limit;
            List<BindingEntry> out = pending.stream()
                    .filter(e -> now.isAfter(e.expiresAt()))
                    .limit(limit)
                    .toList();
            pending.removeAll(out);
            return out;
        }
    }

    static class RecordingHook implements PersistenceHook {
        final List<List<String>> removes = new CopyOnWriteArrayList<>();

        @Override
        public void upsertBatch(List<BindingEntry> batch) {
        }

        @Override
        public void removeBatch(List<String> keys) {
            removes.add(List.copyOf(keys));
        }

        long removedTotal() {
            return removes.stream().mapToInt(List::size).sum();
        }
    }

    private static BindingEntry expired(String key) {
        Instant now = Instant.now();
        return new BindingEntry(key, "hss-pool", "hss-a", "MME-01", "realm", "mme-01-link",
                now.minusSeconds(3600), now.minusSeconds(1));
    }

    private static BindingEntry fresh(String key) {
        Instant now = Instant.now();
        return new BindingEntry(key, "hss-pool", "hss-a", "MME-01", "realm", "mme-01-link",
                now, now.plus(Duration.ofHours(24)));
    }

    @Test
    void sweepRemovesFromPgAndRecordsMetrics() {
        FakeSource source = new FakeSource();
        source.pending.add(expired("IMSI:s1"));
        source.pending.add(expired("IMSI:s2"));
        source.pending.add(fresh("IMSI:keep"));
        RecordingHook hook = new RecordingHook();
        BindingMetricsBridge bridge = new BindingMetricsBridge(new InMemoryBindingStore());

        int removed = BindingSweepJob.sweepOne(source, hook, Instant.now(), 1000);

        assertEquals(2, removed);
        assertEquals(1, hook.removes.size());
        assertEquals(List.of("IMSI:s1", "IMSI:s2"), hook.removes.getFirst());
        bridge.recordSweep(removed);
        assertEquals(1, bridge.sweepRuns().sum());
        assertEquals(2, bridge.sweepExpiredTotal().sum());
    }

    @Test
    void sweepWithoutHookStillSweeps() {
        FakeSource source = new FakeSource();
        source.pending.add(expired("IMSI:nohook"));
        int removed = BindingSweepJob.sweepOne(source, null, Instant.now(), 500);
        assertEquals(1, removed);
    }

    @Test
    void batchSizeLimitPassedThroughToSource() {
        FakeSource source = new FakeSource();
        BindingSweepJob.sweepOne(source, null, Instant.now(), 777);
        assertEquals(777, source.lastLimit);
        assertTrue(source.lastNow != null);
    }
}
