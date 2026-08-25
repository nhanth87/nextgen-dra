package et.elisa.dra.app.sbbs.relay;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

public final class SbbMetrics {

    public static final String DROP_UNKNOWN_TX_TOTAL = "dra_drop_unknown_tx_total";
    public static final String LOOP_DETECTED_TOTAL = "dra_loop_detected_total";
    public static final String REDIRECT_TOTAL = "dra_redirect_total";
    public static final String REJECT_TOTAL = "dra_reject_total";
    public static final String BINDING_CAPTURED_TOTAL = "dra_binding_captured_total";
    public static final String SERVER_INITIATED_FAIL_CLOSED_TOTAL = "dra_server_initiated_fail_closed_total";
    public static final String SCREEN_REJECTED_TOTAL = "dra_screen_rejected_total";
    public static final String SEND_FAILED_TOTAL = "dra_send_failed_total";
    public static final String UNDELIVERED_TOTAL = "dra_unable_to_deliver_total";

    private final ConcurrentMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    public LongAdder counter(String name) {
        return counters.computeIfAbsent(name, n -> new LongAdder());
    }

    public void inc(String name) {
        counter(name).increment();
    }

    public long value(String name) {
        return counter(name).sum();
    }

    public Map<String, Long> snapshot() {
        TreeMap<String, Long> out = new TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
