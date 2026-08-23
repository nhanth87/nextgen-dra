package et.elisa.dra.ra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import et.elisa.dra.core.peer.DraRaPort;
import et.elisa.dra.core.wire.DiaMsg;

/**
 * N-N connection proof over real TCP sockets:
 * 2 ingress MME links (shared listen port) x 2 egress HSS links,
 * CER/CEA + ULR/ULA relay with answer-on-link correlation.
 */
class NNConnectionIntegrationTest {

    private static final int S6A = 16777251;
    private static final long AWAIT_MILLIS = 30_000;

    private final List<AutoCloseable> resources = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (AutoCloseable r : resources) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @Timeout(120)
    void twoIngressTwoEgressRelayRoundTrip() throws Exception {
        RawDiameterEndpoint hssA = RawDiameterEndpoint.listen(0);
        RawDiameterEndpoint hssB = RawDiameterEndpoint.listen(0);
        resources.add(hssA);
        resources.add(hssB);

        int listenPort = freePort();
        int mmeASrc = freePort();
        int mmeBSrc = freePort();

        DiameterRaConfig config = new DiameterRaConfig(
                List.of(
                        new PeerConfig("hss-a", "127.0.0.1", hssA.localPort(), "CLIENT", "TCP",
                                Set.of(S6A), "pool-a", 70, 2000),
                        new PeerConfig("hss-b", "127.0.0.1", hssB.localPort(), "CLIENT", "TCP",
                                Set.of(S6A), "pool-b", 30, 2000),
                        new PeerConfig("mme-a", "127.0.0.1", mmeASrc, listenPort,
                                "mme-a.epc.mnc01.mcc452.3gppnetwork.org",
                                "epc.mnc01.mcc452.3gppnetwork.org",
                                "SERVER", "TCP", Set.of(S6A), "mme-plane", 1, 2000),
                        new PeerConfig("mme-b", "127.0.0.1", mmeBSrc, listenPort,
                                "mme-b.epc.mnc01.mcc452.3gppnetwork.org",
                                "epc.mnc01.mcc452.3gppnetwork.org",
                                "SERVER", "TCP", Set.of(S6A), "mme-plane", 1, 2000)),
                "dra1.epc.mnc01.mcc452.3gppnetwork.org",
                Set.of("epc.mnc01.mcc452.3gppnetwork.org"),
                30000L, 5000L);

        CorsacPeerFabric fabric = new CorsacPeerFabric(config);
        Relay relay = new Relay(fabric);
        fabric.setIngressListener(relay);
        resources.add(fabric::stop);
        fabric.start();

        RawDiameterEndpoint mmeA = RawDiameterEndpoint.connect("127.0.0.1", listenPort, mmeASrc);
        RawDiameterEndpoint mmeB = RawDiameterEndpoint.connect("127.0.0.1", listenPort, mmeBSrc);
        resources.add(mmeA);
        resources.add(mmeB);

        awaitPeerReady(fabric, "hss-a");
        awaitPeerReady(fabric, "hss-b");

        byte[] cerA = RawDiameterEndpoint.cerFrame(101L, 101L,
                "mme-a.epc.mnc01.mcc452.3gppnetwork.org");
        byte[] cerB = RawDiameterEndpoint.cerFrame(201L, 201L,
                "mme-b.epc.mnc01.mcc452.3gppnetwork.org");
        mmeA.send(cerA);
        mmeB.send(cerB);

        byte[] ceaA = mmeA.awaitAnswer(AWAIT_MILLIS);
        byte[] ceaB = mmeB.awaitAnswer(AWAIT_MILLIS);
        assertNotNull(ceaA, "mme-a must receive CEA on its own link");
        assertNotNull(ceaB, "mme-b must receive CEA on shared ingress port");
        assertEquals(RawDiameterEndpoint.CMD_CER, cmdOf(ceaA));
        assertEquals(2001L, resultCodeOf(ceaA));

        awaitPeerReady(fabric, "mme-a");
        awaitPeerReady(fabric, "mme-b");

        relay.route.put("mme-a", "hss-a");
        relay.route.put("mme-b", "hss-b");

        mmeA.send(RawDiameterEndpoint.ulrFrame(7001L, 8001L, "452040100000001", "sess-a-1",
                "mme-a.epc.mnc01.mcc452.3gppnetwork.org"));
        mmeB.send(RawDiameterEndpoint.ulrFrame(7002L, 8002L, "452040200000002", "sess-b-1",
                "mme-b.epc.mnc01.mcc452.3gppnetwork.org"));

        long deadlineHss = System.currentTimeMillis() + AWAIT_MILLIS;
        while (System.currentTimeMillis() < deadlineHss
                && (hssA.requestsSeen() < 2 || hssB.requestsSeen() < 2)) {
            Thread.sleep(100);
        }
        assertTrue(hssA.requestsSeen() >= 2,
                "hss-a must receive forwarded ULR (seen=" + hssA.requestsSeen() + ")");
        assertTrue(hssB.requestsSeen() >= 2,
                "hss-b must receive forwarded ULR (seen=" + hssB.requestsSeen() + ")");

        byte[] ulaA = mmeA.awaitAnswer(AWAIT_MILLIS);
        byte[] ulaB = mmeB.awaitAnswer(AWAIT_MILLIS);
        assertNotNull(ulaA, "mme-a must receive ULA back on its link");
        assertNotNull(ulaB, "mme-b must receive ULA back on its link");

        assertEquals(7001L, hbhOf(ulaA), "ULA for mme-a must carry mme-a original hbh");
        assertEquals(7002L, hbhOf(ulaB), "ULA for mme-b must carry mme-b original hbh");
        assertEquals(8001L, e2eOf(ulaA));
        assertEquals(2001L, resultCodeOf(ulaA));
        assertEquals(2001L, resultCodeOf(ulaB));

        assertEquals(0, relay.unknownAnswers.get(),
                "every egress answer must correlate to a live tx; unknown: " + relay.unknownDetails);
    }

    private static void awaitPeerReady(CorsacPeerFabric fabric, String peerId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            var health = fabric.peersHealth().get(peerId);
            if (health != null && health.ready()) {
                return;
            }
            Thread.sleep(100);
        }
        var health = fabric.peersHealth().get(peerId);
        assertNotNull(health, "peer " + peerId + " must exist in registry");
        assertTrue(health.ready(), "peer " + peerId
                + " did not become ready (state=" + health.state() + ")");
    }

    static int freePort() throws IOException {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int cmdOf(byte[] frame) {
        return ((frame[5] & 0xFF) << 16) | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
    }

    private static long resultCodeOf(byte[] frame) {
        int pos = 20;
        while (pos + 8 <= frame.length) {
            int code = i32(frame, pos);
            boolean vendor = (frame[pos + 4] & 0x80) != 0;
            int len = ((frame[pos + 5] & 0xFF) << 16) | ((frame[pos + 6] & 0xFF) << 8)
                    | (frame[pos + 7] & 0xFF);
            if (code == 268 && !vendor && len == 12 && pos + 12 <= frame.length) {
                return i32(frame, pos + 8) & 0xFFFFFFFFL;
            }
            pos += ((len + 3) & ~3);
        }
        return -1;
    }

    private static long hbhOf(byte[] frame) {
        return i32(frame, 12) & 0xFFFFFFFFL;
    }

    private static long e2eOf(byte[] frame) {
        return i32(frame, 16) & 0xFFFFFFFFL;
    }

    private static int i32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    static final class Relay implements IngressListener {
        private final DraRaPort port;
        final ConcurrentHashMap<String, String> route = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, PendingTx> txByHbhOut = new ConcurrentHashMap<>();
        final AtomicLong unknownAnswers = new AtomicLong();
        private final AtomicLong hbhGen = new AtomicLong(10_000);

        Relay(DraRaPort port) {
            this.port = port;
        }

        @Override
        public void onIngress(IngressEvent event) {
            switch (event) {
                case IngressRequest req -> onRequest(req);
                case IngressAnswer ans -> onAnswer(ans);
            }
        }

        private void onRequest(IngressRequest req) {
            DiaMsg msg = req.msg();
            if (!msg.isRequest()) {
                return;
            }
            if (msg.commandCode() == RawDiameterEndpoint.CMD_ULR) {
                String egress = route.get(req.ingressPeerId());
                if (egress == null) {
                    port.sendAnswerOnLink(req.ingressPeerId(), msg.asAnswer(3002));
                    return;
                }
                long hbhOut = hbhGen.incrementAndGet();
                txByHbhOut.put(hbhOut, new PendingTx(req.ingressPeerId(), msg.hopByHopId()));
                try {
                    port.sendToPeer(egress, msg.withHopByHop(hbhOut));
                } catch (RuntimeException e) {
                    txByHbhOut.remove(hbhOut);
                    port.sendAnswerOnLink(req.ingressPeerId(), msg.asAnswer(3002));
                }
            } else if (msg.commandCode() != RawDiameterEndpoint.CMD_DWR) {
                port.sendAnswerOnLink(req.ingressPeerId(), msg.asAnswer(2001));
            }
        }

        private void onAnswer(IngressAnswer ans) {
            PendingTx tx = txByHbhOut.remove(ans.msg().hopByHopId());
            if (tx == null) {
                unknownAnswers.incrementAndGet();
                unknownDetails.add("hbh=" + ans.msg().hopByHopId() + " cmd=" + ans.msg().commandCode()
                        + " from=" + ans.egressPeerId() + " rc=" + ans.msg().resultCode());
                return;
            }
            port.sendAnswerOnLink(tx.ingressPeerId(), ans.msg().withHopByHop(tx.hbhIn));
        }

        final java.util.Queue<String> unknownDetails = new java.util.concurrent.ConcurrentLinkedQueue<>();
    }

    record PendingTx(String ingressPeerId, long hbhIn) {
    }
}
