package et.elisa.dra.core.overload;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class LoadCache {

    public static final int LOAD_TYPE_HOST = 0;
    public static final int LOAD_TYPE_PEER = 1;

    private final ConcurrentHashMap<String, Integer> hostLoads = new ConcurrentHashMap<>();
    private final LongAdder hostUpdates = new LongAdder();
    private final LongAdder peerReports = new LongAdder();

    public void hostLoad(String peerId, int value) {
        hostLoads.put(peerId, Math.max(0, Math.min(65535, value)));
        hostUpdates.increment();
    }

    public void peerReportObserved() {
        peerReports.increment();
    }

    public Integer loadValue(String peerId) {
        return hostLoads.get(peerId);
    }

    public long hostUpdateCount() {
        return hostUpdates.sum();
    }

    public long peerReportCount() {
        return peerReports.sum();
    }
}
