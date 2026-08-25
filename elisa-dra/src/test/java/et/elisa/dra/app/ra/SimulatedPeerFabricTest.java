package et.elisa.dra.app.ra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

class SimulatedPeerFabricTest {

    private static final int S6A = 16777251;
    private static final int GX = 16777238;

    private DiameterRaConfig config(int maxOutstanding) {
        return new DiameterRaConfig(
                List.of(
                        new PeerConfig("hss-a", "10.0.0.11", 3868, "SERVER", "TCP",
                                Set.of(S6A), "mvno-hss-pool", 70, maxOutstanding),
                        new PeerConfig("hss-b", "10.0.0.12", 3868, "SERVER", "TCP",
                                Set.of(S6A), "mvno-hss-pool", 30, maxOutstanding)),
                "dra1.elisa.lab",
                Set.of("epc.mnc01.mcc452.3gppnetwork.org"),
                0,
                5000);
    }

    private DiaMsg ulr(long hbh) {
        return new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, 316, S6A,
                hbh, 4242L, "sess-1", "mme-01.epc.lab", "epc.mnc01.mcc452.3gppnetwork.org",
                "", "epc.mnc01.mcc452.3gppnetwork.org", 0,
                List.of(DiaAvp.utf8(1, "452040100000001")));
    }

    @Test
    void sendToPeerFailsClosedWhenPeerDown() {
        var fabric = new SimulatedPeerFabric(config(2000));
        assertThrows(PeerNotReadyException.class, () -> fabric.sendToPeer("hss-a", ulr(1)));
        assertEquals(0, fabric.sentCount("hss-a"));
    }

    @Test
    void unknownPeerThrows() {
        var fabric = new SimulatedPeerFabric(config(2000));
        fabric.peerUp("hss-a");
        assertThrows(UnknownPeerException.class, () -> fabric.sendToPeer("ghost", ulr(1)));
    }

    @Test
    void capabilityFilterRejectsUnadvertisedApp() {
        var fabric = new SimulatedPeerFabric(config(2000));
        fabric.peerCea("hss-a", Set.of(S6A));
        DiaMsg gx = new DiaMsg(1, DiaMsg.FLAG_REQUEST, 272, GX, 7L, 8L,
                "", "o", "r", "", "r", 0, List.of());
        assertThrows(AppNotAdvertisedException.class, () -> fabric.sendToPeer("hss-a", gx));
    }

    @Test
    void roundtripRequestAutoAnswerPreservesHbhE2e() {
        var fabric = new SimulatedPeerFabric(config(2000));
        List<IngressEvent> events = new ArrayList<>();
        fabric.setIngressListener(events::add);
        fabric.setAutoAnswer(req -> req.msg().asAnswer(2001)
                .withOrigin("dra1.elisa.lab", "epc.mnc01.mcc452.3gppnetwork.org"));
        fabric.peerUp("hss-a");

        DiaMsg request = ulr(777L);
        fabric.inboundRequest("mme-x", request);

        assertEquals(2, events.size());
        IngressRequest ingress = (IngressRequest) events.get(0);
        assertEquals("mme-x", ingress.ingressPeerId());
        assertEquals(777L, ingress.msg().hopByHopId());
        IngressAnswer answer = (IngressAnswer) events.get(1);
        assertFalse(answer.msg().isRequest());
        assertEquals(777L, answer.msg().hopByHopId());
        assertEquals(2001, answer.msg().resultCode());

        fabric.sendToPeer("hss-a", request.withHopByHop(999L).withOrigin(
                "dra1.elisa.lab", "epc.mnc01.mcc452.3gppnetwork.org"));
        assertEquals(1, fabric.sentCount("hss-a"));
        DiaMsg sent = fabric.takeSent("hss-a");
        assertNotNull(sent);
        assertEquals(999L, sent.hopByHopId());
        assertEquals(4242L, sent.endToEndId());
    }

    @Test
    void sendAnswerOnLinkKeepsHbhE2eIntact() {
        var fabric = new SimulatedPeerFabric(config(2000));
        fabric.peerUp("hss-a");
        DiaMsg answer = ulr(1).asAnswer(3002).withHopByHop(123456L);
        fabric.sendAnswerOnLink("hss-a", answer);
        DiaMsg sent = fabric.takeSent("hss-a");
        assertNotNull(sent);
        assertFalse(sent.isRequest());
        assertEquals(123456L, sent.hopByHopId());
        assertEquals(3002, sent.resultCode());
    }

    @Test
    void answerOnLinkFailsClosedWhenLinkDown() {
        var fabric = new SimulatedPeerFabric(config(2000));
        assertThrows(PeerNotReadyException.class,
                () -> fabric.sendAnswerOnLink("hss-a", ulr(1).asAnswer(2001)));
    }

    @Test
    void admissionGuardRejectsBeyondMaxOutstandingAndRecoversAfterAnswer() {
        var fabric = new SimulatedPeerFabric(config(1));
        fabric.peerUp("hss-a");
        fabric.sendToPeer("hss-a", ulr(1));
        assertThrows(PeerNotReadyException.class, () -> fabric.sendToPeer("hss-a", ulr(2)));
        fabric.injectAnswer("hss-a", ulr(1).asAnswer(2001));
        fabric.clearSent("hss-a");
        fabric.sendToPeer("hss-a", ulr(3));
        assertNotNull(fabric.takeSent("hss-a"));
    }

    @Test
    void peersHealthReflectsSimulatedTruth() {
        var fabric = new SimulatedPeerFabric(config(2000));
        fabric.peerUp("hss-a");
        var health = fabric.peersHealth();
        assertTrue(health.get("hss-a").ready());
        assertEquals("OPEN", health.get("hss-a").state());
        assertFalse(health.get("hss-b").ready());
        assertEquals("IDLE", health.get("hss-b").state());
    }

    @Test
    void takeSentReturnsNullWhenNothingQueued() {
        var fabric = new SimulatedPeerFabric(config(2000));
        assertNull(fabric.takeSent("hss-b"));
    }
}
