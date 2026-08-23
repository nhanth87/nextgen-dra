package et.elisa.dra.core.th;

import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyHiderImplTest {

    private static final String SUFFIX = "epc.mnc01.mcc452.3gppnetwork.org";
    private static final String MME_REAL = "mme-01." + SUFFIX;

    private TopologyHiderImpl newHider(boolean fullEdge) {
        ThConfig config = new ThConfig(SUFFIX, "dra-edge", 4, fullEdge,
                Set.of("ipx-edge"));
        return new TopologyHiderImpl(new PseudoHostMapper(config),
                "dra1." + SUFFIX);
    }

    private DiaMsg ulr(String sessionId) {
        return new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE,
                316, 16777251, 1001L, 42L, sessionId, MME_REAL,
                SUFFIX, "hss-a." + SUFFIX, SUFFIX, 0,
                List.of(
                        DiaAvp.utf8(1, "4520402123456789"),
                        DiaAvp.utf8(282, MME_REAL),
                        DiaAvp.utf8(282, "proxy intermediate"),
                        DiaAvp.utf8(263, sessionId)));
    }

    @Test
    void hideOutboundRewritesIdentityAndStripsRouteRecords() {
        TopologyHiderImpl hider = newHider(false);
        String session = MME_REAL + ";abc;def";
        DiaMsg hidden = hider.hideOutbound(ulr(session), "4520402123456789");

        assertTrue(hidden.originHost().startsWith("dra-edge-"));
        assertTrue(hidden.destinationHost().startsWith("dra-edge-"));
        assertTrue(hidden.sessionId().endsWith(";abc;def"));
        assertTrue(hidden.sessionId().startsWith("dra-edge-"));
        long routeRecords = hidden.avps().stream()
                .filter(a -> a.code() == 282).count();
        assertEquals(0, routeRecords);
        assertEquals("4520402123456789", hidden.avps().get(0).value());
    }

    @Test
    void restoreInboundMapsBackToRealHost() {
        TopologyHiderImpl hider = newHider(false);
        DiaMsg hidden = hider.hideOutbound(
                ulr(MME_REAL + ";abc"), "4520402123456789");
        DiaMsg answer = new DiaMsg(hidden.version(), (byte) 0, hidden.commandCode(),
                hidden.applicationId(), hidden.hopByHopId(), hidden.endToEndId(),
                hidden.sessionId(), hidden.originHost(), SUFFIX,
                hidden.destinationHost(), SUFFIX, 2001,
                List.of());
        DiaMsg restored = hider.restoreInbound(answer);

        assertEquals(MME_REAL, restored.originHost());
        assertEquals(MME_REAL + ";abc", restored.sessionId());
        assertEquals(1, restored.avps().stream()
                .filter(a -> a.code() == 282 && a.value().equals("dra1." + SUFFIX)).count());
    }

    @Test
    void clrStormGuardSameImsiSamePseudoAcrossSessions() {
        TopologyHiderImpl hider = newHider(false);
        DiaMsg first = hider.hideOutbound(
                ulr(MME_REAL + ";s1"), "4520402123456789");
        DiaMsg second = hider.hideOutbound(
                ulr(MME_REAL + ";s2"), "4520402123456789");
        assertEquals(first.originHost(), second.originHost());
        assertEquals(firstHostPart(first.sessionId()), firstHostPart(second.sessionId()));
    }

    @Test
    void fullEdgeBlocksLeakInNestedGroupedAvp() {
        TopologyHiderImpl hider = newHider(true);
        DiaMsg msg = new DiaMsg(1, DiaMsg.FLAG_REQUEST, 316, 16777251,
                1001L, 42L, MME_REAL + ";x", MME_REAL, SUFFIX,
                "hss-a." + SUFFIX, SUFFIX, 0,
                List.of(DiaAvp.grouped(1408, List.of(
                        DiaAvp.utf8(701, "leak." + SUFFIX)))));
        DiaMsg hidden = hider.hideOutbound(msg, "4520402123456789");

        String dumped = String.valueOf(hidden.avps());
        assertTrue(!dumped.contains("leak." + SUFFIX), "leak survived: " + dumped);
        assertTrue(hider.metrics().leakBlocked() >= 1);
    }

    @Test
    void disabledGroupPassthroughUntouched() {
        TopologyHiderImpl hider = newHider(false);
        assertEquals(false, hider.enabledForGroup("internal-pool"));
        DiaMsg external = new DiaMsg(1, DiaMsg.FLAG_REQUEST, 316, 16777251,
                1001L, 42L, "client.example.org;s1", "client.example.org",
                "example.org", null, "example.org", 0,
                List.of(DiaAvp.utf8(1, "4520402123456789")));
        DiaMsg out = hider.hideOutbound(external, "4520402123456789");
        assertEquals(external, out);
    }

    private String firstHostPart(String sessionId) {
        int idx = sessionId.indexOf(';');
        return idx < 0 ? sessionId : sessionId.substring(0, idx);
    }
}
