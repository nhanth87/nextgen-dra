package et.elisa.dra.app.persist;

import et.elisa.dra.core.bind.BindingEntry;
import et.elisa.dra.core.bind.BindingMetricsBridge;
import et.elisa.dra.core.bind.PersistenceHook;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class BindingSweepJob {

    private final Instance<SweepSource> sources;
    private final Instance<PersistenceHook> hooks;
    private final Instance<BindingMetricsBridge> bridges;
    private final int batchSize;

    @Inject
    public BindingSweepJob(Instance<SweepSource> sources,
                           Instance<PersistenceHook> hooks,
                           Instance<BindingMetricsBridge> bridges,
                           @ConfigProperty(name = "dra.bindings.sweep-batch-size", defaultValue = "1000")
                           int batchSize) {
        this.sources = sources;
        this.hooks = hooks;
        this.bridges = bridges;
        this.batchSize = batchSize;
    }

    @Scheduled(every = "{dra.bindings.sweep-every-seconds}s")
    void sweep() {
        if (!sources.isResolvable()) {
            return;
        }
        Instant now = Instant.now();
        PersistenceHook hook = hooks.isResolvable() ? hooks.get() : null;
        BindingMetricsBridge bridge = bridges.isResolvable() ? bridges.get() : null;
        int removedTotal = 0;
        for (SweepSource source : sources) {
            removedTotal += sweepOne(source, hook, now, batchSize);
        }
        if (bridge != null) {
            bridge.recordSweep(removedTotal);
        }
    }

    static int sweepOne(SweepSource source, PersistenceHook hook, Instant now, int limit) {
        List<BindingEntry> expired = source.sweepExpired(now, limit);
        if (hook != null && !expired.isEmpty()) {
            hook.removeBatch(expired.stream().map(BindingEntry::key).toList());
        }
        return expired.size();
    }
}
