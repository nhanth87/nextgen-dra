package et.elisa.dra.core.tx;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TxState {

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    public final long txId = SEQ.incrementAndGet();
    public long hbhIn;
    public long hbhOut;
    public long e2eIn;
    public long e2eOut;
    public String ingressPeerId;
    public volatile String egressPeerId;
    public int applicationId;
    public int commandCode;
    public String sessionId;
    public int drmpPriority;
    public long deadlineMillis;
    public int retryCount;
    public final List<String> triedPeers = new CopyOnWriteArrayList<>();
    public volatile boolean answered;

    public boolean expired(long nowMillis) {
        return nowMillis >= deadlineMillis;
    }

    public boolean tried(String peerId) {
        return triedPeers.contains(peerId);
    }
}
