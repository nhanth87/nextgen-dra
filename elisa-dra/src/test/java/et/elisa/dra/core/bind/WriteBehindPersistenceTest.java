package et.elisa.dra.core.bind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteBehindPersistenceTest {

    static class RecordingHook implements PersistenceHook {
        final List<List<BindingEntry>> upserts = new CopyOnWriteArrayList<>();
        final List<List<String>> removes = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public void upsertBatch(List<BindingEntry> batch) {
            if (failing) {
                throw new IllegalStateException("injected failure");
            }
            upserts.add(List.copyOf(batch));
        }

        @Override
        public void removeBatch(List<String> keys) {
            if (failing) {
                throw new IllegalStateException("injected failure");
            }
            removes.add(List.copyOf(keys));
        }

        int totalUpserts() {
            return upserts.stream().mapToInt(List::size).sum();
        }
    }

    private static BindingEntry entry(String key) {
        Instant now = Instant.now();
        return new BindingEntry(key, "hss-pool", "hss-a", "mme-01", "realm", "mme-link",
                now, now.plus(Duration.ofHours(24)));
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                org.junit.jupiter.api.Assertions.fail("condition not met within 5s");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void submitDoesNotBlockAndFlushWritesBatch() throws Exception {
        RecordingHook hook = new RecordingHook();
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 60_000, 100_000);
        try {
            long t0 = System.nanoTime();
            for (int i = 0; i < 500; i++) {
                wb.submitUpsert(entry("IMSI:" + i));
            }
            long submitNanos = System.nanoTime() - t0;
            assertTrue(submitNanos < TimeUnit.MILLISECONDS.toNanos(200),
                    "submit must not block on IO");
            assertEquals(0, hook.totalUpserts(), "no DB call before flush interval");
            wb.flush();
            await(() -> hook.totalUpserts() == 500);
            assertTrue(hook.upserts.getFirst().size() >= 2, "batched, not per-entry calls");
        } finally {
            wb.close();
        }
    }

    @Test
    void coalescesSameKeyLastWriterWins() throws Exception {
        RecordingHook hook = new RecordingHook();
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 60_000, 100_000);
        try {
            wb.submitUpsert(entry("K1"));
            wb.submitUpsert(entry("K1"));
            wb.submitRemove("K2");
            wb.submitUpsert(entry("K2"));
            wb.flush();
            List<String> keys = hook.upserts.stream().flatMap(List::stream)
                    .map(BindingEntry::key).toList();
            assertEquals(List.of("K1", "K2"), keys.stream().distinct().sorted().toList());
            assertTrue(hook.removes.isEmpty(), "remove overridden by later upsert");
        } finally {
            wb.close();
        }
    }

    @Test
    void removeSurvivesCoalescingWhenLast() throws Exception {
        RecordingHook hook = new RecordingHook();
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 60_000, 100_000);
        try {
            wb.submitUpsert(entry("K9"));
            wb.submitRemove("K9");
            wb.flush();
            assertEquals(0, hook.totalUpserts());
            assertEquals(1, hook.removes.stream().mapToInt(List::size).sum());
            assertEquals(0, wb.upsertedCount());
            assertEquals(1, wb.removedCount());
        } finally {
            wb.close();
        }
    }

    @Test
    @Timeout(10)
    void backgroundThreadFlushesWithoutExplicitFlush() throws Exception {
        RecordingHook hook = new RecordingHook();
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 100, 100_000);
        try {
            wb.submitUpsert(entry("BG1"));
            wb.submitUpsert(entry("BG2"));
            await(() -> hook.totalUpserts() == 2);
        } finally {
            wb.close();
        }
    }

    @Test
    void dropOldestUnderBackpressure() throws Exception {
        RecordingHook hook = new RecordingHook() {
            @Override
            public void upsertBatch(List<BindingEntry> batch) {
                super.upsertBatch(batch);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 50, 8);
        try {
            for (int i = 0; i < 60; i++) {
                wb.submitUpsert(entry("DROP:" + i));
            }
            await(() -> wb.enqueuedCount() == 60);
            Thread.sleep(150);
            assertTrue(wb.droppedOldestCount() > 0,
                    "slow sink must trigger drop-oldest backpressure");
        } finally {
            wb.close();
        }
    }

    @Test
    void hookFailureRetriedOnceThenRecovers() throws Exception {
        RecordingHook hook = new RecordingHook();
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 60_000, 100_000);
        try {
            hook.failing = true;
            wb.submitUpsert(entry("F1"));
            wb.flush();
            assertEquals(1, wb.failedBatchCount());
            assertEquals(1, wb.pendingCount(), "failed batch must be staged for one retry");
            assertEquals(0, hook.totalUpserts());
            hook.failing = false;
            wb.submitUpsert(entry("F2"));
            wb.flush();
            await(() -> hook.totalUpserts() == 2);
            List<String> keys = hook.upserts.stream().flatMap(List::stream)
                    .map(BindingEntry::key).toList();
            assertTrue(keys.contains("F1"), "retried op must eventually land");
            assertTrue(keys.contains("F2"));
            assertEquals(0, wb.pendingCount());
            assertEquals(1, wb.retriedBatchCount());
            assertEquals(0, wb.droppedAfterRetryCount());
        } finally {
            wb.close();
        }
    }

    @Test
    void secondConsecutiveFailureDropsBatchAfterOneRetry() throws Exception {
        RecordingHook hook = new RecordingHook();
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 60_000, 100_000);
        try {
            hook.failing = true;
            wb.submitUpsert(entry("G1"));
            wb.flush();
            wb.flush();
            assertEquals(2, wb.failedBatchCount());
            assertEquals(1, wb.droppedAfterRetryCount(), "batch must be dropped after exactly one retry");
            assertEquals(0, wb.pendingCount());
            wb.flush();
            assertEquals(2, wb.failedBatchCount(), "dropped ops must not be retried again");
        } finally {
            wb.close();
        }
    }

    @Test
    void slowHookDoesNotBlockSubmit() throws Exception {
        RecordingHook hook = new RecordingHook() {
            @Override
            public void upsertBatch(List<BindingEntry> batch) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.upsertBatch(batch);
            }
        };
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 60_000, 100_000);
        try {
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                wb.submitUpsert(entry("SLOW:" + i));
            }
            long submitNanos = System.nanoTime() - t0;
            assertTrue(submitNanos < TimeUnit.MILLISECONDS.toNanos(200),
                    "submit must never wait on the slow persistence hook");
            assertEquals(20, wb.pendingCount());
            wb.flush();
            await(() -> wb.upsertedCount() == 20);
        } finally {
            wb.close();
        }
    }

    @Test
    void retryMergeKeepsNewerOpOverFailedOlderOp() throws Exception {
        class SequenceHook implements PersistenceHook {
            final List<List<BindingEntry>> upserts = new CopyOnWriteArrayList<>();
            volatile int calls;

            @Override
            public void upsertBatch(List<BindingEntry> batch) {
                if (calls++ == 0) {
                    throw new IllegalStateException("first flush fails");
                }
                upserts.add(List.copyOf(batch));
            }

            @Override
            public void removeBatch(List<String> keys) {
            }
        }
        SequenceHook hook = new SequenceHook();
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 60_000, 100_000);
        try {
            wb.submitUpsert(entryWithPeer("R1", "hss-old"));
            wb.flush();
            wb.submitUpsert(entryWithPeer("R1", "hss-new"));
            wb.flush();
            await(() -> !hook.upserts.isEmpty());
            List<BindingEntry> finalBatch = hook.upserts.getLast();
            assertEquals(1, finalBatch.size(), "older retried upsert must not shadow newer staged op");
            assertEquals("hss-new", finalBatch.getFirst().peerId());
        } finally {
            wb.close();
        }
    }

    private static BindingEntry entryWithPeer(String key, String peer) {
        Instant now = Instant.now();
        return new BindingEntry(key, "hss-pool", peer, "mme-01", "realm", "mme-link",
                now, now.plus(Duration.ofHours(24)));
    }

    @Test
    void closeFlushesPendingWork() throws Exception {
        RecordingHook hook = new RecordingHook();
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 3_600_000, 100_000);
        wb.submitUpsert(entry("CLOSE1"));
        wb.submitRemove("CLOSE2");
        wb.close();
        assertEquals(1, hook.totalUpserts());
        assertEquals(1, hook.removes.stream().mapToInt(List::size).sum());
    }

    @Test
    void invalidConfigRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WriteBehindPersistence(new RecordingHook(), 0, 10));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WriteBehindPersistence(new RecordingHook(), 200, -1));
    }

    @Test
    void countersTrackEnqueue() throws Exception {
        RecordingHook hook = new RecordingHook();
        WriteBehindPersistence wb = new WriteBehindPersistence(hook, 60_000, 100_000);
        try {
            for (int i = 0; i < 10; i++) {
                wb.submitUpsert(entry("C:" + i));
            }
            assertEquals(10, wb.enqueuedCount());
            assertEquals(0, wb.droppedOldestCount());
        } finally {
            wb.close();
        }
    }
}
