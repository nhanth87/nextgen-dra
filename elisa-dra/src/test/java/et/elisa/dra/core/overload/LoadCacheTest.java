package et.elisa.dra.core.overload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoadCacheTest {

    @Test
    void hostLoadRoundTrip() {
        LoadCache cache = new LoadCache();
        cache.hostLoad("hss-a", 77);
        assertEquals(77, cache.loadValue("hss-a"));
        assertEquals(1, cache.hostUpdateCount());
    }

    @Test
    void unknownPeerReturnsNull() {
        LoadCache cache = new LoadCache();
        assertNull(cache.loadValue("nobody"));
    }

    @Test
    void hostValueClampedToWireRange() {
        LoadCache cache = new LoadCache();
        cache.hostLoad("hss-a", 99999);
        assertEquals(65535, cache.loadValue("hss-a"));
        cache.hostLoad("hss-b", -3);
        assertEquals(0, cache.loadValue("hss-b"));
    }

    @Test
    void peerTypeIsCounterOnlyNotHostState() {
        LoadCache cache = new LoadCache();
        cache.peerReportObserved();
        cache.peerReportObserved();
        assertNull(cache.loadValue("agent-x"));
        assertEquals(2, cache.peerReportCount());
        assertEquals(0, cache.hostUpdateCount());
    }
}
