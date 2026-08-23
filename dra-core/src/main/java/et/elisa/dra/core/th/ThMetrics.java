package et.elisa.dra.core.th;

import java.util.concurrent.atomic.LongAdder;

public final class ThMetrics {

    public final LongAdder hideTotal = new LongAdder();
    public final LongAdder restoreTotal = new LongAdder();
    public final LongAdder restoreMiss = new LongAdder();
    public final LongAdder leakBlocked = new LongAdder();

    public long hideTotal() {
        return hideTotal.sum();
    }

    public long restoreTotal() {
        return restoreTotal.sum();
    }

    public long restoreMiss() {
        return restoreMiss.sum();
    }

    public long leakBlocked() {
        return leakBlocked.sum();
    }
}
