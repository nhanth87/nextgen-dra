package et.elisa.dra.core.overload;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class OlrCache {

    private record Entry(long seqNum, int reportType, int reductionPercent, Instant validUntil) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;
    private final LongAdder updatesAccepted = new LongAdder();
    private final LongAdder staleIgnored = new LongAdder();
    private final LongAdder expiredPurged = new LongAdder();

    public OlrCache() {
        this(Instant::now);
    }

    public OlrCache(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public void update(String peerId, long seqNum, int reportType, int reductionPct, Instant validUntil) {
        int clamped = Math.max(0, Math.min(100, reductionPct));
        entries.compute(peerId, (k, cur) -> {
            if (cur != null && !acceptsSeq(cur.seqNum(), seqNum)) {
                staleIgnored.increment();
                return cur;
            }
            updatesAccepted.increment();
            return new Entry(seqNum, reportType, clamped, validUntil);
        });
    }

    public int reductionPercentFor(String peerId) {
        Entry e = entries.get(peerId);
        if (e == null) {
            return 0;
        }
        if (!clock.get().isBefore(e.validUntil())) {
            if (entries.remove(peerId, e)) {
                expiredPurged.increment();
            }
            return 0;
        }
        return e.reductionPercent();
    }

    public int maxActiveReduction() {
        Instant now = clock.get();
        int max = 0;
        for (var it = entries.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next().getValue();
            if (!now.isBefore(e.validUntil())) {
                it.remove();
                expiredPurged.increment();
            } else if (e.reductionPercent() > max) {
                max = e.reductionPercent();
            }
        }
        return max;
    }

    public int activeReports() {
        Instant now = clock.get();
        int n = 0;
        for (var it = entries.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next().getValue();
            if (!now.isBefore(e.validUntil())) {
                it.remove();
                expiredPurged.increment();
            } else {
                n++;
            }
        }
        return n;
    }

    public long updatesAcceptedCount() {
        return updatesAccepted.sum();
    }

    public long staleIgnoredCount() {
        return staleIgnored.sum();
    }

    public long expiredPurgedCount() {
        return expiredPurged.sum();
    }

    static boolean acceptsSeq(long stored, long incoming) {
        if (Long.compareUnsigned(incoming, stored) > 0) {
            return true;
        }
        long band = Long.divideUnsigned(-1L, 100L);
        boolean fromTop = Long.compareUnsigned(stored, -1L - band) >= 0;
        boolean toBottom = Long.compareUnsigned(incoming, band) <= 0;
        return fromTop && toBottom;
    }
}
