package et.elisa.dra.core.bind;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;

public final class ClusteredBindingStore implements BindingStore {

    private final BindingStore local;
    private final ReplicationHook replication;
    private final Executor replicationExecutor;
    private final LongAdder replicatedPuts = new LongAdder();
    private final LongAdder replicatedRemoves = new LongAdder();
    private final LongAdder replicationFailures = new LongAdder();

    public ClusteredBindingStore(BindingStore local) {
        this(local, ReplicationHook.noop(), null);
    }

    public ClusteredBindingStore(BindingStore local, ReplicationHook replication) {
        this(local, replication, null);
    }

    public ClusteredBindingStore(BindingStore local, ReplicationHook replication, Executor replicationExecutor) {
        this.local = Objects.requireNonNull(local);
        this.replication = Objects.requireNonNull(replication);
        this.replicationExecutor = replicationExecutor;
    }

    @Override
    public Optional<BindingEntry> get(String key) {
        return local.get(key);
    }

    @Override
    public void put(BindingEntry entry) {
        local.put(entry);
        dispatch(() -> {
            replication.onPut(entry);
            replicatedPuts.increment();
        });
    }

    @Override
    public boolean remove(String key) {
        boolean removed = local.remove(key);
        if (removed) {
            dispatch(() -> {
                replication.onRemove(key);
                replicatedRemoves.increment();
            });
        }
        return removed;
    }

    @Override
    public long size() {
        return local.size();
    }

    private void dispatch(Runnable task) {
        if (replicationExecutor == null) {
            runQuietly(task);
        } else {
            replicationExecutor.execute(() -> runQuietly(task));
        }
    }

    private void runQuietly(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            replicationFailures.increment();
        }
    }

    public long replicatedPutCount() {
        return replicatedPuts.sum();
    }

    public long replicatedRemoveCount() {
        return replicatedRemoves.sum();
    }

    public long replicationFailureCount() {
        return replicationFailures.sum();
    }
}
