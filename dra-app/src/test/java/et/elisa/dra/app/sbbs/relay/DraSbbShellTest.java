package et.elisa.dra.app.sbbs.relay;

import com.microjainslee.api.SleeEvent;
import et.elisa.dra.app.sbbs.DraBindingSbb;
import et.elisa.dra.app.sbbs.DraOverloadSbb;
import et.elisa.dra.app.sbbs.DraRelaySbb;
import et.elisa.dra.app.sbbs.RaEventBridge;
import et.elisa.dra.core.bind.PeerRouteTarget;
import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.common.DraResultCodes;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraSbbShellTest {

    private static final String SELF = RelayCoreTest.SELF;
    private static final String REALM = RelayCoreTest.REALM;

    private static RaEventBridge bridgeOf(AtomicReference<DiaMsg> reqOut,
                                          AtomicReference<String> reqPeer,
                                          AtomicReference<DiaMsg> ansOut,
                                          AtomicReference<String> ansPeer) {
        return new RaEventBridge() {
            @Override
            public Optional<IngressRequest> asRequest(SleeEvent event) {
                if (event instanceof Marker m && reqOut != null) {
                    reqOut.set(m.msg);
                    reqPeer.set("mme-01");
                    return Optional.of(new IngressRequest("mme-01", m.msg));
                }
                return Optional.empty();
            }

            @Override
            public Optional<IngressAnswer> asAnswer(SleeEvent event) {
                if (event instanceof AnswerMarker a && ansOut != null) {
                    ansOut.set(a.msg);
                    ansPeer.set("hss-a");
                    return Optional.of(new IngressAnswer(a.msg, "hss-a"));
                }
                return Optional.empty();
            }
        };
    }

    @Test
    void relayShellForwardsRequestAndCorrelatesAnswer() {
        RelayCoreTest.Stack s = new RelayCoreTest.Stack();
        RelayCore core = s.core();
        AtomicReference<DiaMsg> reqIn = new AtomicReference<>();
        AtomicReference<String> reqPeer = new AtomicReference<>();
        AtomicReference<DiaMsg> ansIn = new AtomicReference<>();
        AtomicReference<String> ansPeer = new AtomicReference<>();
        DraRelaySbb sbb = new DraRelaySbb(core, bridgeOf(reqIn, reqPeer, ansIn, ansPeer));

        DiaMsg req = RelayCoreTest.request(316, 2001, 20, "shell-1", "mme.host");
        sbb.onEvent(new Marker(req), null);

        assertEquals(1, s.ra.requests.size());
        assertEquals("hss-a", s.ra.requests.get(0).peerId());
        long hbhOut = s.ra.requests.get(0).msg().hopByHopId();

        sbb.onEvent(new AnswerMarker(
                req.asAnswer(DraResultCodes.SUCCESS).withHopByHop(hbhOut)), null);

        assertEquals(1, s.ra.answers.size());
        assertEquals(2001L, s.ra.answers.get(0).msg().hopByHopId());
        assertEquals(0, s.table.activeCount());
    }

    @Test
    void bindingShellDrivesServerInitiatedAndSweep() {
        RelayCoreTest.Stack s = new RelayCoreTest.Stack();
        s.siTarget.set(Optional.of(new PeerRouteTarget("mvno-hss-pool", "mme-01", "MME-01.real")));
        RelayCore core = s.core();
        DraBindingSbb sbb = new DraBindingSbb(core, new RaEventBridge() {
            @Override
            public Optional<IngressRequest> asRequest(SleeEvent event) {
                return event instanceof Marker m
                        ? Optional.of(new IngressRequest("hss-a", m.msg))
                        : Optional.empty();
            }

            @Override
            public Optional<IngressAnswer> asAnswer(SleeEvent event) {
                return Optional.empty();
            }
        }, () -> RelayCoreTest.Stack.BASE_MILLIS + 3_600_000L);

        DiaMsg idr = RelayCoreTest.request(319, 2101, 21, "shell-2", "edge.pseudo");
        idr = AvpOps.withDestinationHost(idr, "dra-edge-1.pseudo");
        sbb.onEvent(new Marker(idr), null);

        assertEquals(1, s.ra.requests.size());
        assertEquals("mme-01", s.ra.requests.get(0).peerId());
        assertEquals("MME-01.real", s.ra.requests.get(0).msg().destinationHost());

        s.ra.requests.clear();
        s.siTarget.set(Optional.empty());
        DiaMsg clr = RelayCoreTest.request(317, 2201, 22, "shell-3", "hss-a.host");
        sbb.onEvent(new Marker(clr), null);
        assertTrue(s.ra.requests.isEmpty());
        assertEquals(DraResultCodes.UNABLE_TO_DELIVER, s.ra.answers.get(0).msg().resultCode());

        sbb.onSweepTick();
        assertEquals(0, s.table.activeCount());
    }

    @Test
    void overloadShellExposesGateAndDelegatesRequests() {
        RelayCoreTest.Stack s = new RelayCoreTest.Stack();
        RelayCore core = s.core();
        DraOverloadSbb sbb = new DraOverloadSbb(core, new RaEventBridge() {
            @Override
            public Optional<IngressRequest> asRequest(SleeEvent event) {
                return event instanceof Marker m
                        ? Optional.of(new IngressRequest("mme-01", m.msg))
                        : Optional.empty();
            }

            @Override
            public Optional<IngressAnswer> asAnswer(SleeEvent event) {
                return Optional.empty();
            }
        }, s.gate);

        assertTrue(((PassGate) sbb.gate()).admitAll.get());
        s.gate.admitAll.set(false);

        sbb.onEvent(new Marker(RelayCoreTest.request(316, 2301, 23, "shell-4", "mme.host")), null);

        assertTrue(s.ra.requests.isEmpty());
        assertEquals(DraResultCodes.TOO_BUSY, s.ra.answers.get(0).msg().resultCode());
    }

    private record Marker(DiaMsg msg) implements SleeEvent {
    }

    private record AnswerMarker(DiaMsg msg) implements SleeEvent {
    }
}
