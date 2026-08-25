package et.elisa.dra.core.overload;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

public final class AdmissionController {

    private static final long UNITS_PER_TOKEN = 1_000_000L;
    private static final double NANOS_PER_SEC = 1_000_000_000d;
    private static final int MAX_COMPENSATION_ROUNDS = 32;

    private final ConcurrentHashMap<String, Bucket> peerBuckets = new ConcurrentHashMap<>();
    private final Bucket global;
    private final double globalRatePerSec;
    private final double peerRatePerSec;
    private final long peerCapacityUnits;
    private final LongSupplier nanoClock;
    private final LongAdder admitted = new LongAdder();
    private final LongAdder throttled = new LongAdder();

    public AdmissionController(double globalRatePerSec, double globalBurst,
                               double peerRatePerSec, double peerBurst) {
        this(globalRatePerSec, globalBurst, peerRatePerSec, peerBurst, System::nanoTime);
    }

    public AdmissionController(double globalRatePerSec, double globalBurst,
                               double peerRatePerSec, double peerBurst, LongSupplier nanoClock) {
        if (globalBurst < 1 || peerBurst < 1) {
            throw new IllegalArgumentException("burst must be >= 1");
        }
        if (globalRatePerSec < 0 || peerRatePerSec < 0) {
            throw new IllegalArgumentException("rate must be >= 0");
        }
        this.globalRatePerSec = globalRatePerSec;
        this.peerRatePerSec = peerRatePerSec;
        this.peerCapacityUnits = (long) (peerBurst * UNITS_PER_TOKEN);
        this.nanoClock = nanoClock;
        this.global = new Bucket(globalRatePerSec * UNITS_PER_TOKEN,
                (long) (globalBurst * UNITS_PER_TOKEN), nanoClock.getAsLong());
    }

    public boolean tryAcquire(String ingressPeerId, int drmpPriority, int cmdCode,
                              Set<Integer> criticalCommands) {
        return tryAcquire(ingressPeerId, drmpPriority, cmdCode, criticalCommands, 1.0d);
    }

    public boolean tryAcquire(String ingressPeerId, int drmpPriority, int cmdCode,
                              Set<Integer> criticalCommands, double abatementScale) {
        boolean critical = DrmpPolicy.isCriticalCommand(cmdCode, criticalCommands);
        int effectivePriority = critical ? DrmpPolicy.MIN_PRIORITY : DrmpPolicy.clamp(drmpPriority);
        Bucket peer = peerBuckets.computeIfAbsent(ingressPeerId,
                k -> new Bucket(peerRatePerSec * UNITS_PER_TOKEN, peerCapacityUnits,
                        nanoClock.getAsLong()));
        long now = nanoClock.getAsLong();
        double scale = Math.max(0d, Math.min(1d, abatementScale));
        double fraction = Math.min(global.fraction(now, scale), peer.fraction(now, scale));
        int cutoff = (int) Math.floor(fraction * DrmpPolicy.TIERS) - 1;
        if (effectivePriority > cutoff) {
            throttled.increment();
            return false;
        }
        for (int round = 0; round < MAX_COMPENSATION_ROUNDS; round++) {
            if (!global.tryConsume(now, scale)) {
                throttled.increment();
                return false;
            }
            if (peer.tryConsume(now, scale)) {
                admitted.increment();
                return true;
            }
            global.refund();
        }
        throttled.increment();
        return false;
    }

    public long admittedCount() {
        return admitted.sum();
    }

    public long throttledCount() {
        return throttled.sum();
    }

    private static final class Bucket {
        private final AtomicLong units;
        private final AtomicLong lastNanos;
        private final long capacityUnits;
        private final double rateUnitsPerSec;

        Bucket(double rateUnitsPerSec, long capacityUnits, long nowNanos) {
            this.rateUnitsPerSec = rateUnitsPerSec;
            this.capacityUnits = capacityUnits;
            this.units = new AtomicLong(capacityUnits);
            this.lastNanos = new AtomicLong(nowNanos);
        }

        void refill(long nowNanos, double scale) {
            long last = lastNanos.get();
            long elapsed = nowNanos - last;
            if (elapsed <= 0) {
                lastNanos.compareAndSet(last, nowNanos);
                return;
            }
            double gained = elapsed * rateUnitsPerSec * scale / NANOS_PER_SEC;
            if (gained > 0) {
                units.updateAndGet(u -> (long) Math.min((double) capacityUnits, (double) u + gained));
            }
            lastNanos.compareAndSet(last, nowNanos);
        }

        boolean tryConsume(long nowNanos, double scale) {
            refill(nowNanos, scale);
            for (; ; ) {
                long cur = units.get();
                if (cur < UNITS_PER_TOKEN) {
                    return false;
                }
                if (units.compareAndSet(cur, cur - UNITS_PER_TOKEN)) {
                    return true;
                }
            }
        }

        void refund() {
            units.updateAndGet(u -> (long) Math.min((double) capacityUnits, (double) u + UNITS_PER_TOKEN));
        }

        double fraction(long nowNanos, double scale) {
            refill(nowNanos, scale);
            return units.get() / (double) capacityUnits;
        }
    }
}
