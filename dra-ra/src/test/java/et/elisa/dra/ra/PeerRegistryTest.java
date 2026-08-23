package et.elisa.dra.ra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import et.elisa.dra.core.peer.PeerHealth;

class PeerRegistryTest {

    private static final int S6A = 16777251;

    private DiameterRaConfig config() {
        return new DiameterRaConfig(
                List.of(
                        new PeerConfig("hss-a", "10.0.0.11", 3868, "SERVER", "TCP",
                                Set.of(S6A), "mvno-hss-pool", 70, 2000),
                        new PeerConfig("mme-01", "10.0.1.5", 3868, "CLIENT", "TCP",
                                Set.of(S6A), "mme-pool", 30, 500)),
                "dra1.elisa.lab",
                Set.of("epc.mnc01.mcc452.3gppnetwork.org"),
                0,
                5000);
    }

    @Test
    void seededPeersStartIdleAndNotReady() {
        var registry = new PeerRegistry(config());
        Map<String, PeerHealth> health = registry.healthMap();
        assertEquals(Set.of("hss-a", "mme-01"), health.keySet());
        assertEquals("IDLE", health.get("hss-a").state());
        assertFalse(health.get("hss-a").ready());
        assertFalse(registry.isReady("hss-a"));
    }

    @Test
    void channelUpAloneIsNotReady() {
        var registry = new PeerRegistry(config());
        registry.onChannelUp("hss-a");
        PeerHealth h = registry.healthMap().get("hss-a");
        assertTrue(h.channelUp());
        assertFalse(h.ceaOk());
        assertEquals("CER_SENT", h.state());
        assertFalse(h.ready());
    }

    @Test
    void ceaCompletesReadinessTruth() {
        var registry = new PeerRegistry(config());
        registry.onChannelUp("hss-a");
        registry.onCeaAccepted("hss-a", Set.of(S6A));
        PeerHealth h = registry.healthMap().get("hss-a");
        assertTrue(h.ready());
        assertEquals("OPEN", h.state());
        assertEquals(Set.of(S6A), h.advertisedApps());
    }

    @Test
    void watchdogInvalidBreaksReadyEvenWhenFlagsUp() throws InterruptedException {
        var cfg = new DiameterRaConfig(List.of(
                new PeerConfig("hss-a", "10.0.0.11", 3868, "SERVER", "TCP",
                        Set.of(S6A), "g", 1, 100)),
                "dra1.elisa.lab", Set.of("epc.lab"), 20, 5000);
        var registry = new PeerRegistry(cfg);
        registry.onChannelUp("hss-a");
        registry.onCeaAccepted("hss-a", Set.of(S6A));
        assertTrue(registry.isReady("hss-a"));
        Thread.sleep(60);
        PeerHealth h = registry.healthMap().get("hss-a");
        assertTrue(h.channelUp());
        assertTrue(h.ceaOk());
        assertFalse(h.watchdogValid());
        assertFalse(h.ready());
    }

    @Test
    void channelDownMarksDownAndClearsReadiness() {
        var registry = new PeerRegistry(config());
        registry.onChannelUp("hss-a");
        registry.onCeaAccepted("hss-a", Set.of(S6A));
        assertTrue(registry.isReady("hss-a"));
        registry.onChannelDown("hss-a");
        PeerHealth h = registry.healthMap().get("hss-a");
        assertEquals("DOWN", h.state());
        assertFalse(h.channelUp());
        assertFalse(h.ceaOk());
        assertFalse(h.ready());
    }

    @Test
    void reconnectCycleReturnsToOpen() {
        var registry = new PeerRegistry(config());
        registry.onChannelDown("hss-a");
        registry.onChannelUp("hss-a");
        registry.onCeaAccepted("hss-a", Set.of(S6A));
        assertTrue(registry.isReady("hss-a"));
    }

    @Test
    void requireDeliverableRejectsUnknownPeer() {
        var registry = new PeerRegistry(config());
        assertThrows(UnknownPeerException.class, () -> registry.requireDeliverable("ghost", S6A));
    }

    @Test
    void requireDeliverableRejectsNotReadyPeer() {
        var registry = new PeerRegistry(config());
        assertThrows(PeerNotReadyException.class, () -> registry.requireDeliverable("hss-a", S6A));
    }

    @Test
    void requireDeliverableRejectsUnadvertisedApp() {
        var registry = new PeerRegistry(config());
        registry.onChannelUp("hss-a");
        registry.onCeaAccepted("hss-a", Set.of(S6A));
        assertThrows(AppNotAdvertisedException.class,
                () -> registry.requireDeliverable("hss-a", 16777238));
        assertTrue(registry.requireDeliverable("hss-a", S6A) != null);
    }

    @Test
    void requireOpenLinkSkipsCapabilityFilterForAnswers() {
        var registry = new PeerRegistry(config());
        registry.onChannelUp("hss-a");
        registry.onCeaAccepted("hss-a", Set.of(S6A));
        assertTrue(registry.requireOpenLink("hss-a") != null);
        registry.onChannelDown("hss-a");
        assertThrows(PeerNotReadyException.class, () -> registry.requireOpenLink("hss-a"));
    }
}
