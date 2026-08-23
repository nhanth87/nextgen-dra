package et.elisa.dra.app.bootstrap;

import com.microjainslee.core.MicroSleeContainer;

import et.elisa.dra.app.sbbs.relay.FixedCandidates;
import et.elisa.dra.app.sbbs.relay.FakeEngine;
import et.elisa.dra.app.sbbs.relay.FakeTh;
import et.elisa.dra.app.sbbs.relay.PassGate;
import et.elisa.dra.app.sbbs.relay.PassScreen;
import et.elisa.dra.app.sbbs.relay.RelayCore;
import et.elisa.dra.app.sbbs.relay.RelayCoreTest;
import et.elisa.dra.core.bind.InMemoryBindingStore;
import et.elisa.dra.core.bind.ServerInitiatedResolverImpl;
import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.overload.AdmissionController;
import et.elisa.dra.core.overload.LoadCache;
import et.elisa.dra.core.overload.OlrCache;
import et.elisa.dra.core.overload.OverloadGateImpl;
import et.elisa.dra.core.screen.ScreeningConfig;
import et.elisa.dra.core.screen.ScreeningServiceImpl;
import et.elisa.dra.core.th.PseudoHostMapper;
import et.elisa.dra.core.th.ThConfig;
import et.elisa.dra.core.th.TopologyHiderImpl;
import et.elisa.dra.core.tx.DefaultTxTable;
import et.elisa.dra.core.tx.HbhAllocator;
import et.elisa.dra.core.tx.RelaySupport;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import et.elisa.dra.ra.DiameterRaConfig;
import et.elisa.dra.ra.IngressRequest;
import et.elisa.dra.ra.PeerConfig;
import et.elisa.dra.ra.SimulatedPeerFabric;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the strict micro-jainslee plane: ingress fires through the RA
 * bootstrap port into MicroSleeContainer, routes via mapEventToSbb/IES into
 * DraRelaySbb.$Concrete (CMP + @InjectRa bound), and the relayed ULA returns
 * on the ingress link with hop-by-hop correlation.
 */
class DraBootstrapContainerTest {

    private static final String SELF = RelayCoreTest.SELF;
    private static final String REALM = RelayCoreTest.REALM;

    private MicroSleeContainer container;
    private SimulatedPeerFabric fabric;
    private DraBootstrap bootstrap;

    @AfterEach
    void down() {
        if (bootstrap != null) {
            bootstrap.close();
            bootstrap = null;
        }
    }

    @Test
    void ingressRelayRoundTripThroughContainer() throws Exception {
        DiameterRaConfig config = new DiameterRaConfig(
                List.of(
                        new PeerConfig("mme-01", "127.0.0.1", 40001, null,
                                "mme-01." + REALM, REALM, "SERVER", "TCP",
                                Set.of(16777251), "mme-plane", 1, 100),
                        new PeerConfig("hss-a", "127.0.0.1", 40002, null,
                                "hss-a." + REALM, REALM, "CLIENT", "TCP",
                                Set.of(16777251), "mvno-hss-pool", 1, 100)),
                SELF, Set.of(REALM), 30_000L, 5_000L);

        fabric = new SimulatedPeerFabric(config);

        AtomicReference<Optional<et.elisa.dra.core.bind.PeerRouteTarget>> siTarget =
                new AtomicReference<>(Optional.empty());
        FixedCandidates candidates = new FixedCandidates();
        candidates.byGroup.put("mvno-hss-pool", List.of("hss-a"));
        FakeEngine engine = new FakeEngine(SELF);
        engine.forwardTo("mvno-hss-pool", "hss-a");
        RelayCore core = new RelayCore(engine, new DefaultTxTable(),
                new HbhAllocator(), fabric, new InMemoryBindingStore(),
                ctx -> siTarget.get(), new PassGate(), new PassScreen(), new FakeTh(),
                candidates, SELF, REALM, new RelaySupport(5_000, 1),
                new AtomicLong(System.currentTimeMillis())::incrementAndGet);

        container = new MicroSleeContainer();
        bootstrap = new DraBootstrap(container, core, fabric);
        bootstrap.init();
        fabric.setIngressListener(bootstrap.endpoint()::onRaIngress);
        fabric.peerCea("hss-a", Set.of(16777251));
        fabric.peerCea("mme-01", Set.of(16777251));

        DiaMsg ulr = new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, 316, 16777251,
                7001L, 8001L, "sess-container-1", "mme-01." + REALM, REALM, null, REALM, 0,
                List.of(DiaAvp.utf8(AvpCodes.USER_NAME, "452040210000001")));

        fabric.inboundRequest("mme-01", ulr);

        DiaMsg forwarded = await(() -> fabric.takeSent("hss-a"));
        assertNotNull(forwarded, "request must be forwarded to hss-a");
        assertTrue(forwarded.hopByHopId() != 7001L, "egress hbh must be re-allocated");

        // wire-realistic: HSS answers only after receiving the forwarded request
        DiaMsg hssUla = new DiaMsg(1, 0x20, 316, forwarded.applicationId(),
                forwarded.hopByHopId(), forwarded.endToEndId(),
                forwarded.sessionId(), "hss-a." + REALM, REALM, null, null, 2001,
                List.of(DiaAvp.uint32(AvpCodes.RESULT_CODE, 2001)));
        fabric.injectAnswer("hss-a", hssUla);

        DiaMsg ula = await(() -> fabric.takeSent("mme-01"));
        assertNotNull(ula, "ULA must be answered on the mme-01 link");
        assertEquals(7001L, ula.hopByHopId(), "ingress hbh must be restored");
        assertEquals(8001L, ula.endToEndId(), "end-to-end id must be preserved");
        assertEquals(2001, ula.resultCode());
        assertEquals("hss-a." + REALM, ula.originHost());

        assertNotNull(core.commandPort(),
                "@InjectRa must bind the RA command port into the relay core");

        long deadline = System.currentTimeMillis() + 5_000;
        while (core.txActive() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, core.txActive(), "tx table must drain after answer");
    }

    @FunctionalInterface
    interface Supplier {
        DiaMsg get();
    }

    private static DiaMsg await(Supplier poller) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        DiaMsg msg = poller.get();
        while (msg == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
            msg = poller.get();
        }
        return msg;
    }
}
