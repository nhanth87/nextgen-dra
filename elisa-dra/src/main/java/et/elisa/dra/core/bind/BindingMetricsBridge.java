package et.elisa.dra.core.bind;

import et.elisa.dra.core.metrics.MetricsNames;

import java.util.concurrent.atomic.LongAdder;

public final class BindingMetricsBridge {

    private final InMemoryBindingStore store;
    private final LongAdder sweepRuns = new LongAdder();
    private final LongAdder sweepExpiredTotal = new LongAdder();

    public BindingMetricsBridge(InMemoryBindingStore store) {
        this.store = store;
    }

    public long gaugeValue(String name) {
        if (!MetricsNames.BINDING_SIZE.equals(name)) {
            throw new IllegalArgumentException("unknown binding gauge: " + name);
        }
        return store.size();
    }

    public long bindingSize() {
        return store.size();
    }

    public void recordSweep(int expiredRemoved) {
        sweepRuns.increment();
        sweepExpiredTotal.add(expiredRemoved);
    }

    public LongAdder sweepRuns() {
        return sweepRuns;
    }

    public LongAdder sweepExpiredTotal() {
        return sweepExpiredTotal;
    }

    public long storeExpiredCount() {
        return store.expiredCount();
    }

    public long storeEvictedCount() {
        return store.evictedCount();
    }
}
