package et.elisa.dra.app.persist;

import et.elisa.dra.core.bind.BindingEntry;
import et.elisa.dra.core.bind.BindingStore;
import et.elisa.dra.core.bind.ClusteredBindingStore;
import et.elisa.dra.core.bind.InMemoryBindingStore;
import et.elisa.dra.core.bind.PgBindingDao;
import et.elisa.dra.core.bind.PersistenceHook;
import et.elisa.dra.core.bind.ReplicationHook;
import et.elisa.dra.core.bind.WriteBehindPersistence;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Owns the durable binding plane: in-memory LRU front + write-behind
 * persistence into dra_binding (PG prod / H2 demo). Exposes itself as
 * {@link SweepSource} and {@link PersistenceHook} so BindingSweepJob
 * discovers them via CDI without extra producers.
 */
@Singleton
@RegisterForReflection
public class DurableBindings implements SweepSource, PersistenceHook {

    private static final Logger LOG = LogManager.getLogger(DurableBindings.class);

    private final DataSource dataSource;
    private final long flushIntervalMillis;
    private final int maxBuffered;

    private volatile InMemoryBindingStore local;
    private volatile ClusteredBindingStore clustered;
    private volatile WriteBehindPersistence writeBehind;

    @Inject
    public DurableBindings(DataSource dataSource,
            @ConfigProperty(name = "dra.bindings.writebehind.interval-millis", defaultValue = "500")
            long flushIntervalMillis,
            @ConfigProperty(name = "dra.bindings.writebehind.max-buffered", defaultValue = "100000")
            int maxBuffered) {
        this.dataSource = dataSource;
        this.flushIntervalMillis = flushIntervalMillis;
        this.maxBuffered = maxBuffered;
    }

    /** Builds (once) and returns the durable binding store, preloading rows from DB. */
    public synchronized BindingStore store() {
        if (clustered != null) {
            return clustered;
        }
        local = new InMemoryBindingStore();
        var dao = new PgBindingDao(dataSource);
        writeBehind = new WriteBehindPersistence(dao, flushIntervalMillis, maxBuffered);
        clustered = new ClusteredBindingStore(local, new ReplicationHook() {
            @Override
            public void onPut(BindingEntry entry) {
                writeBehind.submitUpsert(entry);
            }

            @Override
            public void onRemove(String key) {
                writeBehind.submitRemove(key);
            }
        });
        int preloaded = 0;
        try {
            for (BindingEntry e : dao.loadNotExpired(Instant.now())) {
                local.put(e);
                preloaded++;
            }
        } catch (RuntimeException e) {
            LOG.warn("[durable-bindings] preload failed (continuing empty): {}", e.toString());
        }
        LOG.info("[durable-bindings] durable store live (preloaded={}, flush={}ms, buffer={})",
                preloaded, flushIntervalMillis, maxBuffered);
        return clustered;
    }

    @Override
    public List<BindingEntry> sweepExpired(Instant now, int limit) {
        InMemoryBindingStore mem = local;
        return mem == null ? List.of() : mem.sweepExpired(now, limit);
    }

    @Override
    public void upsertBatch(List<BindingEntry> batch) {
        WriteBehindPersistence wb = writeBehind;
        if (wb == null || batch == null) {
            return;
        }
        batch.forEach(wb::submitUpsert);
    }

    @Override
    public void removeBatch(List<String> keys) {
        WriteBehindPersistence wb = writeBehind;
        if (wb == null || keys == null) {
            return;
        }
        keys.forEach(wb::submitRemove);
    }

    /** Most recent entries for the admin dashboard. */
    public List<Map<String, Object>> sample(int limit) {
        InMemoryBindingStore mem = local;
        if (mem == null || limit <= 0) {
            return List.of();
        }
        Instant now = Instant.now();
        return mem.entries(limit).stream()
                .map(e -> Map.<String, Object>of(
                        "key", e.key(),
                        "groupId", e.groupId() == null ? "" : e.groupId(),
                        "peerId", e.peerId() == null ? "" : e.peerId(),
                        "originHost", e.originHost() == null ? "" : e.originHost(),
                        "ingressPeerId", e.ingressPeerId() == null ? "" : e.ingressPeerId(),
                        "createdAt", e.createdAt() == null ? "" : e.createdAt().toString(),
                        "expiresAt", e.expiresAt() == null ? "" : e.expiresAt().toString(),
                        "expired", e.expiredAt(now)))
                .toList();
    }

    public boolean durable() {
        return clustered != null;
    }

    public long size() {
        BindingStore s = clustered;
        return s == null ? 0 : s.size();
    }

    @PreDestroy
    void shutdown() {
        WriteBehindPersistence wb = writeBehind;
        writeBehind = null;
        if (wb != null) {
            try {
                wb.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
