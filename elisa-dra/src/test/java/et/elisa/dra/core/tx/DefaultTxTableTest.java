package et.elisa.dra.core.tx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTxTableTest {

    private static TxState tx(long hbhOut, long deadline) {
        TxState t = new TxState();
        t.hbhIn = hbhOut + 100_000;
        t.hbhOut = hbhOut;
        t.deadlineMillis = deadline;
        t.commandCode = 316;
        t.ingressPeerId = "mme-01";
        return t;
    }

    @Test
    void putGetRemoveRoundTrip() {
        DefaultTxTable table = new DefaultTxTable();
        TxState t = tx(7, 1_000);
        table.put(t);
        assertSame(t, table.byHbhOut(7));
        assertEquals(1, table.activeCount());
        assertSame(t, table.remove(7));
        assertNull(table.byHbhOut(7));
        assertEquals(0, table.activeCount());
    }

    @Test
    void removeUnknownKeyReturnsNullWithoutTouchingCounter() {
        DefaultTxTable table = new DefaultTxTable();
        assertNull(table.remove(42));
        assertEquals(0, table.activeCount());
    }

    @Test
    void duplicateHbhOutDoesNotOverwriteLiveEntry() {
        DefaultTxTable table = new DefaultTxTable();
        TxState first = tx(9, Long.MAX_VALUE);
        TxState second = tx(9, Long.MAX_VALUE);
        table.put(first);
        table.put(second);
        assertSame(first, table.byHbhOut(9));
        assertEquals(1, table.activeCount());
    }

    @Test
    void forEachExpiredDeliversOnlyExpiredAndRemovesThem() {
        DefaultTxTable table = new DefaultTxTable();
        table.put(tx(1, 100));
        table.put(tx(2, 199));
        table.put(tx(3, 5_000));
        List<Long> swept = new ArrayList<>();
        table.forEachExpired(200, t -> swept.add(t.hbhOut));
        assertEquals(List.of(1L, 2L), swept);
        assertEquals(1, table.activeCount());
        assertNotNull(table.byHbhOut(3));
        assertNull(table.byHbhOut(1));
        assertNull(table.byHbhOut(2));
    }

    @Test
    void expiredBoundaryInclusive() {
        DefaultTxTable table = new DefaultTxTable();
        table.put(tx(5, 1_000));
        List<Long> swept = new ArrayList<>();
        table.forEachExpired(1_000, t -> swept.add(t.hbhOut));
        assertEquals(List.of(5L), swept);
        assertEquals(0, table.activeCount());
    }

    @Test
    void concurrentSweepsDeliverEachExpiredTxExactlyOnce() throws Exception {
        DefaultTxTable table = new DefaultTxTable();
        int total = 500;
        for (int i = 1; i <= total; i++) {
            table.put(tx(i, 50));
        }
        AtomicInteger deliveries = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int r = 0; r < 20; r++) {
                    table.forEachExpired(100, t -> deliveries.incrementAndGet());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(total, deliveries.get());
        assertEquals(0, table.activeCount());
    }

    @Test
    void stressActiveCountStaysConsistentUnderChurn() throws Exception {
        DefaultTxTable table = new DefaultTxTable();
        int threads = 8;
        int perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int th = 0; th < threads; th++) {
            int base = th * perThread;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        long key = base + i + 1;
                        table.put(tx(key, Long.MAX_VALUE));
                        assertNotNull(table.byHbhOut(key));
                        table.remove(key);
                    }
                } finally {
                    done.countDown();
                }
                return null;
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, table.activeCount());
    }

    @Test
    void leakGuardReturnsToZeroAfterMixedChurnWithSweeper() throws Exception {
        DefaultTxTable table = new DefaultTxTable();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(3);
        Future<?> churnA = pool.submit(() -> {
            try {
                for (int i = 0; i < 5_000; i++) {
                    long key = 1_000_000L + i;
                    table.put(tx(key, Long.MAX_VALUE));
                    if ((i & 1) == 0) {
                        table.remove(key);
                    }
                }
                for (int i = 1; i < 5_000; i += 2) {
                    table.remove(1_000_000L + i);
                }
            } finally {
                done.countDown();
            }
            return null;
        });
        Future<?> churnB = pool.submit(() -> {
            try {
                for (int i = 0; i < 5_000; i++) {
                    long key = 2_000_000L + i;
                    table.put(tx(key, Long.MAX_VALUE));
                    table.remove(key);
                }
            } finally {
                done.countDown();
            }
            return null;
        });
        Future<?> sweeper = pool.submit(() -> {
            try {
                for (int r = 0; r < 200; r++) {
                    table.forEachExpired(r * 10, t -> { });
                }
            } finally {
                done.countDown();
            }
            return null;
        });
        churnA.get(30, TimeUnit.SECONDS);
        churnB.get(30, TimeUnit.SECONDS);
        sweeper.get(30, TimeUnit.SECONDS);
        done.await();
        pool.shutdown();
        assertEquals(0, table.activeCount());
    }
}
