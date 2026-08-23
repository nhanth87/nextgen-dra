package et.elisa.dra.ra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

class CorsacPeerFabricTest {

    private static final int S6A = 16777251;
    private static final int CX = 16777216;

    private DiameterRaConfig config() {
        return new DiameterRaConfig(
                List.of(
                        new PeerConfig("hss-a", "10.0.0.11", 3868, "SERVER", "TCP",
                                Set.of(S6A), "mvno-hss-pool", 70, 2000),
                        new PeerConfig("hss-b", "10.0.0.12", 3868, "CLIENT", "SCTP",
                                Set.of(S6A), "mvno-hss-pool", 30, 2000)),
                "dra1.elisa.lab",
                Set.of("epc.mnc01.mcc452.3gppnetwork.org"),
                30000,
                5000);
    }

    private DiaMsg ulr() {
        return new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, 316, S6A,
                42L, 4242L, "sess-9", "mme-01.epc.lab", "epc.mnc01.mcc452.3gppnetwork.org",
                "", "epc.mnc01.mcc452.3gppnetwork.org", 0,
                List.of(DiaAvp.utf8(1, "452040100000001")));
    }

    @Test
    void configSeedsRegistryWithAllPeersDown() {
        var fabric = new CorsacPeerFabric(config());
        var health = fabric.peersHealth();
        assertEquals(Set.of("hss-a", "hss-b"), health.keySet());
        assertFalse(health.get("hss-a").ready());
        assertEquals("IDLE", health.get("hss-a").state());
        assertTrue(fabric.link("hss-a") == null);
    }

    @Test
    void unknownPeerFailsClosedBeforeTransportTouch() {
        var fabric = new CorsacPeerFabric(config());
        assertThrows(UnknownPeerException.class, () -> fabric.sendToPeer("ghost", ulr()));
        assertThrows(UnknownPeerException.class,
                () -> fabric.sendAnswerOnLink("ghost", ulr().asAnswer(2001)));
    }

    @Test
    void downPeerFailsClosedWithoutOpeningSocket() {
        var fabric = new CorsacPeerFabric(config());
        assertThrows(PeerNotReadyException.class, () -> fabric.sendToPeer("hss-a", ulr()));
    }

    @Test
    void capabilityGateRunsBeforeTransportGate() {
        var fabric = new CorsacPeerFabric(config());
        fabric.registry().onChannelUp("hss-a");
        fabric.registry().onCeaAccepted("hss-a", Set.of(S6A));
        DiaMsg cx = new DiaMsg(1, DiaMsg.FLAG_REQUEST, 301, CX, 5L, 6L,
                "", "o", "r", "", "r", 0, List.of());
        assertThrows(AppNotAdvertisedException.class, () -> fabric.sendToPeer("hss-a", cx));
        assertThrows(PeerNotReadyException.class,
                () -> fabric.sendToPeer("hss-a", ulr()),
                "ready peer without live corsac link still fails closed");
    }

    @Test
    void answerGatingRequiresOpenLinkOnly() {
        var fabric = new CorsacPeerFabric(config());
        fabric.registry().onChannelUp("hss-b");
        fabric.registry().onCeaAccepted("hss-b", Set.of(S6A));
        DiaMsg answer = ulr().withHopByHop(31337L).asAnswer(2001);
        assertThrows(PeerNotReadyException.class, () -> fabric.sendAnswerOnLink("hss-b", answer));
    }

    @Test
    void sendToPeerRejectsNonRequestMessage() {
        var fabric = new CorsacPeerFabric(config());
        fabric.registry().onChannelUp("hss-a");
        fabric.registry().onCeaAccepted("hss-a", Set.of(S6A));
        assertThrows(IllegalArgumentException.class,
                () -> fabric.sendToPeer("hss-a", ulr().asAnswer(2001)));
    }

    @Test
    void healthTruthFollowsObservationHooks() {
        var fabric = new CorsacPeerFabric(config());
        fabric.registry().onChannelUp("hss-a");
        fabric.registry().onCeaAccepted("hss-a", Set.of(S6A));
        var h = fabric.peersHealth().get("hss-a");
        assertNotNull(h);
        assertTrue(h.ready());
        assertEquals("OPEN", h.state());
        assertEquals(Set.of(S6A), h.advertisedApps());
        fabric.registry().onChannelDown("hss-a");
        assertEquals("DOWN", fabric.peersHealth().get("hss-a").state());
        assertFalse(fabric.peersHealth().get("hss-a").ready());
    }

    @Test
    void startStopLifecycleIsIdempotentAndSafeOnBadAddress() {
        var cfg = new DiameterRaConfig(
                List.of(new PeerConfig("bad-peer", "256.256.256.256", 3868, "SERVER", "TCP",
                        Set.of(S6A), "g", 1, 10)),
                "dra1.elisa.lab", Set.of("epc.lab"), 30000, 5000);
        var fabric = new CorsacPeerFabric(cfg);
        assertThrows(IllegalStateException.class, fabric::start);
        fabric.stop();
        fabric.stop();
        assertFalse(fabric.peersHealth().get("bad-peer").ready());
    }
}
