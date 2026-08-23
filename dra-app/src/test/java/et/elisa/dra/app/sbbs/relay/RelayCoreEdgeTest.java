package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.app.sbbs.relay.RelayCoreTest.Stack;
import et.elisa.dra.core.bind.PeerRouteTarget;
import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.common.DraResultCodes;
import et.elisa.dra.core.engine.ThMode;
import et.elisa.dra.core.metrics.MetricsNames;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayCoreEdgeTest {

    @Test
    void screenerRejectionNeverForwards() {
        Stack s = new Stack();
        s.screen.blockCode.set(5012);
        RelayCore core = s.core();

        core.onRequest("mme-01", RelayCoreTest.request(316, 1001, 10, "s1", "mme"));

        assertTrue(s.ra.requests.isEmpty());
        assertEquals(1, s.ra.answers.size());
        FakeRaPort.Sent ans = s.ra.answers.get(0);
        assertEquals("mme-01", ans.peerId());
        assertEquals(5012, ans.msg().resultCode());
        assertEquals(1001L, ans.msg().hopByHopId());
        assertEquals(0, s.table.activeCount());
        assertEquals(1, core.metrics().value(SbbMetrics.SCREEN_REJECTED_TOTAL));
    }

    @Test
    void loopDetectedWhenRouteRecordContainsSelf() {
        Stack s = new Stack();
        RelayCore core = s.core();
        DiaMsg req = AvpOps.append(RelayCoreTest.request(316, 1101, 11, "s2", "mme"),
                DiaAvp.utf8(AvpCodes.ROUTE_RECORD, RelayCoreTest.self()));

        core.onRequest("mme-01", req);

        assertTrue(s.ra.requests.isEmpty());
        assertEquals(1, s.ra.answers.size());
        assertEquals(DraResultCodes.LOOP_DETECTED, s.ra.answers.get(0).msg().resultCode());
        assertEquals(1, core.metrics().value(SbbMetrics.LOOP_DETECTED_TOTAL));
    }

    @Test
    void admissionRejectAnswersTooBusy() {
        Stack s = new Stack();
        s.gate.admitAll.set(false);
        RelayCore core = s.core();

        core.onRequest("mme-01", RelayCoreTest.request(316, 1201, 12, "s3", "mme"));

        assertTrue(s.ra.requests.isEmpty());
        assertEquals(1, s.ra.answers.size());
        assertEquals(DraResultCodes.TOO_BUSY, s.ra.answers.get(0).msg().resultCode());
        assertEquals(1, core.metrics().value(MetricsNames.THROTTLED_TOTAL));
    }

    @Test
    void serverInitiatedWithoutBindingAndDestinationHostFailsClosed() {
        Stack s = new Stack();
        RelayCore core = s.core();
        DiaMsg clr = RelayCoreTest.request(317, 1301, 13, "s4", "hss-a.host");

        core.serverInitiated("hss-a", clr);

        assertTrue(s.ra.requests.isEmpty());
        assertEquals(1, s.ra.answers.size());
        FakeRaPort.Sent ans = s.ra.answers.get(0);
        assertEquals("hss-a", ans.peerId());
        assertEquals(DraResultCodes.UNABLE_TO_DELIVER, ans.msg().resultCode());
        assertEquals(1, core.metrics().value(SbbMetrics.SERVER_INITIATED_FAIL_CLOSED_TOTAL));
    }

    @Test
    void serverInitiatedUsesBindingTargetRewritesDestinationHostAndRestoresTh() {
        Stack s = new Stack();
        s.th.enabledGroups.add("mvno-hss-pool");
        s.siTarget.set(Optional.of(new PeerRouteTarget("mvno-hss-pool", "mme-01", "MME-01.real")));
        RelayCore core = s.core();
        DiaMsg idr = AvpOps.append(
                new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, 319, 16777216,
                        1401, 14, "s5", "dra-edge-1.pseudo", RelayCoreTest.realm(),
                        "dra-edge-1.pseudo", RelayCoreTest.realm(), 0, List.of()),
                DiaAvp.utf8(FakeTh.MARKER_OUT, "out:452040210000001"));

        core.serverInitiated("hss-a", idr);

        assertEquals(1, s.ra.requests.size());
        FakeRaPort.Sent sent = s.ra.requests.get(0);
        assertEquals("mme-01", sent.peerId());
        assertEquals("MME-01.real", sent.msg().destinationHost());
        assertEquals(Optional.of("MME-01.real"),
                AvpOps.firstUtf8(sent.msg(), AvpCodes.DESTINATION_HOST));
        long hbhOut = sent.msg().hopByHopId();
        assertTrue(hbhOut > 0);
        assertTrue(hbhOut != 1401L);

        core.onAnswer(idr.asAnswer(DraResultCodes.SUCCESS).withHopByHop(hbhOut), "mme-01");

        assertEquals(1, s.ra.answers.size());
        FakeRaPort.Sent back = s.ra.answers.get(0);
        assertEquals("hss-a", back.peerId());
        assertEquals(1401L, back.msg().hopByHopId());
        assertEquals(Optional.of("in"), AvpOps.firstUtf8(back.msg(), FakeTh.MARKER_IN));
        assertTrue(AvpOps.firstUtf8(back.msg(), FakeTh.MARKER_OUT).isEmpty());
        assertEquals(0, s.table.activeCount());
        assertEquals(0, core.metrics().value(SbbMetrics.BINDING_CAPTURED_TOTAL));
    }

    @Test
    void thHideOutboundAppliedAndAnswerRestoredForClientDirection() {
        Stack s = new Stack();
        s.th.enabledGroups.add("mvno-hss-pool");
        s.engine.decision.set(new et.elisa.dra.core.engine.RouteDecision.Forward(
                "mvno-hss-pool", null, true, ThMode.PSEUDO_HOST_DETERMINISTIC,
                List.of(), "hss-a"));
        RelayCore core = s.core();
        DiaMsg ulr = RelayCoreTest.request(316, 1501, 15, "s6", "mme.host");

        core.onRequest("mme-01", ulr);

        assertEquals(1, s.ra.requests.size());
        DiaMsg out = s.ra.requests.get(0).msg();
        assertTrue(AvpOps.firstUtf8(out, FakeTh.MARKER_OUT).isPresent());
        long hbhOut = out.hopByHopId();

        core.onAnswer(ulr.asAnswer(DraResultCodes.SUCCESS).withHopByHop(hbhOut), "hss-a");

        assertEquals(1, s.ra.answers.size());
        DiaMsg back = s.ra.answers.get(0).msg();
        assertEquals(Optional.of("in"), AvpOps.firstUtf8(back, FakeTh.MARKER_IN));
        assertTrue(AvpOps.firstUtf8(back, FakeTh.MARKER_OUT).isEmpty());
        assertEquals(1501L, back.hopByHopId());
    }

    @Test
    void forwardWithoutPeerAndWithoutStickyFailsClosed() {
        Stack s = new Stack();
        s.engine.decision.set(new et.elisa.dra.core.engine.RouteDecision.Forward(
                "mvno-hss-pool", null, true, ThMode.OFF, List.of(), null));
        RelayCore core = s.core();

        core.onRequest("mme-01", RelayCoreTest.request(316, 1601, 16, "s7", "mme.host"));

        assertTrue(s.ra.requests.isEmpty());
        assertEquals(1, s.ra.answers.size());
        assertEquals(DraResultCodes.UNABLE_TO_DELIVER, s.ra.answers.get(0).msg().resultCode());
        assertEquals(0, s.table.activeCount());
    }

    @Test
    void stickyBindingHitOverridesPreferredPeer() {
        Stack s = new Stack();
        String key = et.elisa.dra.core.bind.BindingKeys.IMSI + ":452040210000003";
        s.bindings.put(new et.elisa.dra.core.bind.BindingEntry(key, "mvno-hss-pool", "hss-b",
                "mme.host", RelayCoreTest.realm(), "mme-01",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600)));
        s.engine.decision.set(new et.elisa.dra.core.engine.RouteDecision.Forward(
                "mvno-hss-pool", new et.elisa.dra.core.engine.StickyBinding(key, 3600),
                true, ThMode.OFF, List.of(), "hss-a"));
        RelayCore core = s.core();

        core.onRequest("mme-01",
                new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, 316, 16777216,
                        1701, 17, "s8", "mme.host", RelayCoreTest.realm(), null,
                        RelayCoreTest.realm(), 0,
                        List.of(DiaAvp.utf8(AvpCodes.USER_NAME, "452040210000003"))));

        assertEquals(1, s.ra.requests.size());
        assertEquals("hss-b", s.ra.requests.get(0).peerId());
    }

    @Test
    void rejectDecisionAnswersConfiguredCode() {
        Stack s = new Stack();
        s.engine.decision.set(new et.elisa.dra.core.engine.RouteDecision.Reject(
                DraResultCodes.REALM_NOT_SERVED, "unknown realm"));
        RelayCore core = s.core();

        core.onRequest("mme-01", RelayCoreTest.request(316, 1801, 18, "s9", "mme.host"));

        assertTrue(s.ra.requests.isEmpty());
        assertEquals(1, s.ra.answers.size());
        assertEquals(DraResultCodes.REALM_NOT_SERVED, s.ra.answers.get(0).msg().resultCode());
        assertEquals(1, core.metrics().value(SbbMetrics.REJECT_TOTAL));
    }
}
