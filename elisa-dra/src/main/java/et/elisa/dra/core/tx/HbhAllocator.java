package et.elisa.dra.core.tx;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

public final class HbhAllocator {

    public static final int MAX_HBH = Integer.MAX_VALUE;
    public static final int DEFAULT_MAX_PROBES = 1024;

    private final AtomicInteger wheel;
    private final int maxProbes;

    public HbhAllocator() {
        this(new AtomicInteger(0), DEFAULT_MAX_PROBES);
    }

    public HbhAllocator(AtomicInteger wheel, int maxProbes) {
        if (maxProbes < 1) {
            throw new IllegalArgumentException("maxProbes must be >= 1");
        }
        this.wheel = wheel;
        this.maxProbes = maxProbes;
    }

    public long next(LongPredicate occupied) {
        for (int i = 0; i < maxProbes; i++) {
            long candidate = nextRaw();
            if (!occupied.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("hbh wheel saturated: no free slot after " + maxProbes + " probes");
    }

    private long nextRaw() {
        for (;;) {
            int prev = wheel.get();
            int nxt = prev >= MAX_HBH ? 1 : prev + 1;
            if (wheel.compareAndSet(prev, nxt)) {
                return nxt;
            }
        }
    }
}
