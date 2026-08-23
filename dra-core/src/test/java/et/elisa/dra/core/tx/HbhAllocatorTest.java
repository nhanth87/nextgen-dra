package et.elisa.dra.core.tx;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HbhAllocatorTest {

    @Test
    void valuesArePositive31Bit() {
        HbhAllocator allocator = new HbhAllocator();
        for (int i = 0; i < 10_000; i++) {
            long v = allocator.next(x -> false);
            assertTrue(v >= 1 && v <= HbhAllocator.MAX_HBH, "out of range: " + v);
        }
    }

    @Test
    void noDuplicatesUnderMultithreadedPressure() throws Exception {
        HbhAllocator allocator = new HbhAllocator();
        int threads = 8;
        int perThread = 25_000;
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        seen.add(allocator.next(x -> false));
                    }
                } finally {
                    done.countDown();
                }
                return null;
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals((long) threads * perThread, seen.size());
    }

    @Test
    void skipsOccupiedLiveEntries() {
        HbhAllocator allocator = new HbhAllocator();
        Set<Long> occupied = Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        long v = allocator.next(occupied::contains);
        assertEquals(11L, v);
    }

    @Test
    void saturationThrowsInsteadOfBlocking() {
        HbhAllocator allocator = new HbhAllocator(new AtomicInteger(0), 64);
        assertThrows(IllegalStateException.class, () -> allocator.next(x -> true));
    }

    @Test
    void wheelWrapsAroundMaxIntWithoutEmittingZeroOrNegative() {
        AtomicInteger wheel = new AtomicInteger(Integer.MAX_VALUE - 3);
        HbhAllocator allocator = new HbhAllocator(wheel, 1024);
        Set<Long> values = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            long v = allocator.next(x -> false);
            assertTrue(v >= 1 && v <= HbhAllocator.MAX_HBH);
            values.add(v);
        }
        assertEquals(8, values.size());
    }

    @Test
    void probesSkipWrappedCollisionWindow() {
        AtomicInteger wheel = new AtomicInteger(Integer.MAX_VALUE - 2);
        HbhAllocator allocator = new HbhAllocator(wheel, 1024);
        Set<Long> occupied = ConcurrentHashMap.newKeySet();
        long first = allocator.next(occupied::contains);
        occupied.add(first);
        long second = allocator.next(occupied::contains);
        assertTrue(first != second);
        assertTrue(first >= 1 && first <= HbhAllocator.MAX_HBH);
        assertTrue(second >= 1 && second <= HbhAllocator.MAX_HBH);
    }

    @Test
    void invalidMaxProbesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HbhAllocator(new AtomicInteger(), 0));
    }

    @Test
    void concurrentAllocationNeverCollidesOnFreshWheelRegion() throws Exception {
        HbhAllocator allocator = new HbhAllocator();
        AtomicInteger collisions = new AtomicInteger();
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 50_000; i++) {
                        long v = allocator.next(x -> false);
                        if (!seen.add(v)) {
                            collisions.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
                return null;
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, collisions.get());
        assertEquals(200_000, seen.size());
    }
}
