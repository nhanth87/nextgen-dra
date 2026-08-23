package et.elisa.dra.core.bind;

import et.elisa.dra.core.metrics.MetricsNames;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BindingMetricsBridgeTest {

    @Test
    void gaugeTracksStoreSizeAndSweepCountersAccumulate() {
        InMemoryBindingStore store = new InMemoryBindingStore();
        BindingMetricsBridge bridge = new BindingMetricsBridge(store);
        assertEquals(0, bridge.gaugeValue(MetricsNames.BINDING_SIZE));
        Instant now = Instant.now();
        store.put(new BindingEntry("E1", "g", "p", "oh", "or", "ip",
                now.minusSeconds(60), now.minusSeconds(1)));
        store.put(new BindingEntry("A1", "g", "p", "oh", "or", "ip",
                now, now.plusSeconds(3600)));
        int removed = store.sweepExpired(now).size();
        bridge.recordSweep(removed);
        assertEquals(1, bridge.gaugeValue(MetricsNames.BINDING_SIZE));
        assertEquals(1, bridge.sweepRuns().sum());
        assertEquals(1, bridge.sweepExpiredTotal().sum());
        assertEquals(1, bridge.storeExpiredCount());
    }

    @Test
    void unknownGaugeRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new BindingMetricsBridge(new InMemoryBindingStore()).gaugeValue("dra_other"));
    }
}
