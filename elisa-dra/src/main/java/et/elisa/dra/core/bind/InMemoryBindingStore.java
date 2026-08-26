package et.elisa.dra.core.bind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

public final class InMemoryBindingStore implements BindingStore {

    private final Map<String, BindingEntry> lru;
    private final int maxEntries;
    private final LongAdder sizeCounter = new LongAdder();
    private final LongAdder expiredTotal = new LongAdder();
    private final LongAdder evictedTotal = new LongAdder();

    public InMemoryBindingStore() {
        this(500_000);
    }

    public InMemoryBindingStore(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.lru = new LinkedHashMap<>(1024, 0.75f, true);
    }

    @Override
    public Optional<BindingEntry> get(String key) {
        synchronized (lru) {
            BindingEntry entry = lru.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.expiredAt(Instant.now())) {
                lru.remove(key);
                sizeCounter.decrement();
                expiredTotal.increment();
                return Optional.empty();
            }
            return Optional.of(entry);
        }
    }

    @Override
    public void put(BindingEntry entry) {
        synchronized (lru) {
            if (lru.put(entry.key(), entry) == null) {
                sizeCounter.increment();
            }
            if (lru.size() > maxEntries) {
                Iterator<String> it = lru.keySet().iterator();
                while (lru.size() > maxEntries && it.hasNext()) {
                    it.next();
                    it.remove();
                    sizeCounter.decrement();
                    evictedTotal.increment();
                }
            }
        }
    }

    @Override
    public boolean remove(String key) {
        synchronized (lru) {
            if (lru.remove(key) != null) {
                sizeCounter.decrement();
                return true;
            }
            return false;
        }
    }

    @Override
    public long size() {
        return sizeCounter.sum();
    }

    public List<BindingEntry> sweepExpired(Instant now) {
        return sweepExpired(now, Integer.MAX_VALUE);
    }

    public List<BindingEntry> sweepExpired(Instant now, int limit) {
        List<BindingEntry> removed = new ArrayList<>();
        synchronized (lru) {
            Iterator<Map.Entry<String, BindingEntry>> it = lru.entrySet().iterator();
            while (it.hasNext() && removed.size() < limit) {
                Map.Entry<String, BindingEntry> me = it.next();
                if (me.getValue().expiredAt(now)) {
                    removed.add(me.getValue());
                    it.remove();
                    sizeCounter.decrement();
                    expiredTotal.increment();
                }
            }
        }
        return removed;
    }

    public int capacity() {
        return maxEntries;
    }

    /** Most recently used entries, newest last, bounded by limit. */
    public List<BindingEntry> entries(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (lru) {
            int from = Math.max(0, lru.size() - limit);
            return lru.values().stream().skip(from).toList();
        }
    }

    public long expiredCount() {
        return expiredTotal.sum();
    }

    public long evictedCount() {
        return evictedTotal.sum();
    }
}
