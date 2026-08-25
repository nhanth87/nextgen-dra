package et.elisa.dra.core.bind;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class WriteBehindPersistence implements AutoCloseable {

    private sealed interface Op permits Upsert, Remove {

        long seq();
    }

    private record Upsert(BindingEntry entry, long seq) implements Op {
    }

    private record Remove(String key, long seq) implements Op {
    }

    private final PersistenceHook hook;
    private final long flushIntervalMs;
    private final int maxBuffered;
    private final BlockingQueue<Op> queue = new LinkedBlockingQueue<>();
    private final LinkedHashMap<String, Op> staging = new LinkedHashMap<>();
    private LinkedHashMap<String, Op> retryStage;
    private final Object stagingLock = new Object();
    private final AtomicLong opSeq = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;
    private final LongAdder enqueuedTotal = new LongAdder();
    private final LongAdder droppedOldestTotal = new LongAdder();
    private final LongAdder upsertedTotal = new LongAdder();
    private final LongAdder removedTotal = new LongAdder();
    private final LongAdder flushedBatches = new LongAdder();
    private final LongAdder failedBatches = new LongAdder();
    private final LongAdder retriedBatches = new LongAdder();
    private final LongAdder droppedAfterRetryTotal = new LongAdder();
    private volatile boolean flushRequested;

    public WriteBehindPersistence(PersistenceHook hook) {
        this(hook, 200, 100_000);
    }

    public WriteBehindPersistence(PersistenceHook hook, long flushIntervalMs, int maxBuffered) {
        if (flushIntervalMs <= 0 || maxBuffered <= 0) {
            throw new IllegalArgumentException("flushIntervalMs and maxBuffered must be positive");
        }
        this.hook = hook;
        this.flushIntervalMs = flushIntervalMs;
        this.maxBuffered = maxBuffered;
        this.worker = Thread.ofVirtual().name("dra-binding-write-behind").start(this::drainLoop);
    }

    public void submitUpsert(BindingEntry entry) {
        enqueue(new Upsert(entry, opSeq.incrementAndGet()));
    }

    public void submitRemove(String key) {
        enqueue(new Remove(key, opSeq.incrementAndGet()));
    }

    private void enqueue(Op op) {
        enqueuedTotal.increment();
        while (true) {
            if (buffered() < maxBuffered && queue.offer(op)) {
                return;
            }
            Op evicted = evictOldestStaged();
            if (evicted == null) {
                evicted = queue.poll();
            }
            if (evicted != null) {
                droppedOldestTotal.increment();
            }
            if (queue.offer(op)) {
                return;
            }
        }
    }

    private Op evictOldestStaged() {
        synchronized (stagingLock) {
            Iterator<Op> it = staging.values().iterator();
            if (!it.hasNext()) {
                return null;
            }
            Op oldest = it.next();
            it.remove();
            return oldest;
        }
    }

    private int buffered() {
        synchronized (stagingLock) {
            return queue.size() + staging.size();
        }
    }

    public int pendingCount() {
        synchronized (stagingLock) {
            return queue.size() + staging.size() + (retryStage == null ? 0 : retryStage.size());
        }
    }

    private void drainLoop() {
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(flushIntervalMs);
        long deadline = System.nanoTime() + intervalNanos;
        while (running) {
            drainQueueIntoStaging();
            if (System.nanoTime() >= deadline) {
                doFlush();
                deadline = System.nanoTime() + intervalNanos;
            }
            if (flushRequested) {
                drainQueueIntoStaging();
                doFlush();
                flushRequested = false;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                // woken for explicit flush or shutdown — fall through
            }
        }
        drainQueueIntoStaging();
        doFlush();
    }

    private static String keyOf(Op op) {
        return switch (op) {
            case Upsert u -> u.entry().key();
            case Remove r -> r.key();
        };
    }

    private void stage(Op op) {
        synchronized (stagingLock) {
            staging.merge(keyOf(op), op, WriteBehindPersistence::newerOp);
        }
    }

    private static Op newerOp(Op current, Op candidate) {
        return candidate.seq() >= current.seq() ? candidate : current;
    }

    private void drainQueueIntoStaging() {
        Op op;
        while ((op = queue.poll()) != null) {
            stage(op);
        }
    }

    /**
     * Single-writer discipline: only the worker thread mutates staging/applies
     * batches. Callers just wake it and wait until everything enqueued before
     * this call has been applied — no concurrent flush interleavings.
     */
    public void flush() {
        flushRequested = true;
        worker.interrupt();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (flushRequested && System.nanoTime() < deadline) {
            if (!worker.isAlive()) {
                break;
            }
            Thread.onSpinWait();
        }
    }

    private void doFlush() {
        doFlushLocked();
    }

    private void doFlushLocked() {
        LinkedHashMap<String, Op> batch;
        boolean includesRetry;
        synchronized (stagingLock) {
            includesRetry = retryStage != null && !retryStage.isEmpty();
            if (includesRetry) {
                for (Map.Entry<String, Op> e : retryStage.entrySet()) {
                    staging.merge(e.getKey(), e.getValue(), WriteBehindPersistence::newerOp);
                }
                retryStage = null;
            }
            if (staging.isEmpty()) {
                return;
            }
            batch = new LinkedHashMap<>(staging);
            staging.clear();
        }
        List<BindingEntry> upserts = new ArrayList<>();
        List<String> removes = new ArrayList<>();
        for (Op op : batch.values()) {
            switch (op) {
                case Upsert u -> upserts.add(u.entry());
                case Remove r -> removes.add(r.key());
            }
        }
        try {
            applyBatches(upserts, removes);
        } catch (RuntimeException e) {
            failedBatches.increment();
            if (includesRetry) {
                droppedAfterRetryTotal.add(batch.size());
            } else {
                synchronized (stagingLock) {
                    if (retryStage == null) {
                        retryStage = batch;
                    } else {
                        for (Map.Entry<String, Op> entry : batch.entrySet()) {
                            retryStage.merge(entry.getKey(), entry.getValue(), WriteBehindPersistence::newerOp);
                        }
                    }
                }
                retriedBatches.increment();
            }
        }
    }

    private void applyBatches(List<BindingEntry> upserts, List<String> removes) {
        if (!upserts.isEmpty()) {
            hook.upsertBatch(upserts);
            upsertedTotal.add(upserts.size());
            flushedBatches.increment();
        }
        if (!removes.isEmpty()) {
            hook.removeBatch(removes);
            removedTotal.add(removes.size());
            flushedBatches.increment();
        }
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        flushRequested = false;
        worker.interrupt();
        worker.join(5_000);
        drainQueueIntoStaging();
        doFlush();
    }

    public long enqueuedCount() {
        return enqueuedTotal.sum();
    }

    public long droppedOldestCount() {
        return droppedOldestTotal.sum();
    }

    public long upsertedCount() {
        return upsertedTotal.sum();
    }

    public long removedCount() {
        return removedTotal.sum();
    }

    public long flushedBatchCount() {
        return flushedBatches.sum();
    }

    public long failedBatchCount() {
        return failedBatches.sum();
    }

    public long retriedBatchCount() {
        return retriedBatches.sum();
    }

    public long droppedAfterRetryCount() {
        return droppedAfterRetryTotal.sum();
    }
}
