package et.elisa.dra.core.overload;

import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverloadGateImplTest {

    private static DiaAvp u32(int code, long v) {
        return DiaAvp.uint32(code, v);
    }

    private static DiaAvp u64(int code, long v) {
        return new DiaAvp(code, 0, false, DiaAvp.TYPE_UINT64, v, null, null);
    }

    private static DiaMsg answer(List<DiaAvp> avps) {
        return new DiaMsg(1, DiaMsg.FLAG_PROXYABLE, 316, 16777251, 1L, 2L,
                "sess-1", "hss-a.epc.mnc01.mcc452.3gppnetwork.org",
                "epc.mnc01.mcc452.3gppnetwork.org", null,
                "epc.mnc01.mcc452.3gppnetwork.org", 2001, avps);
    }

    @Test
    void endToEndAdmitThenOlrReductionHalvesAdmission() {
        long[] nano = {0L};
        AtomicReference<Instant> wall = new AtomicReference<>(Instant.parse("2026-08-23T00:00:00Z"));
        OlrCache olr = new OlrCache(wall::get);
        LoadCache loads = new LoadCache();
        AdmissionController admission = new AdmissionController(1000, 1, 1000, 1, () -> nano[0]);
        OverloadGateImpl gate = new OverloadGateImpl(olr, loads, admission, wall::get);

        int before = 0;
        for (int i = 0; i < 1000; i++) {
            nano[0] += 1_000_000L;
            if (gate.admit("mme-01", 5)) {
                before++;
            }
        }
        assertEquals(1000, before);

        DiaMsg olrAnswer = answer(List.of(
                DiaAvp.grouped(DoicAvps.OC_SUPPORTED_FEATURES, List.of(u64(DoicAvps.OC_FEATURE_VECTOR, 1L))),
                DiaAvp.grouped(DoicAvps.OC_OLR, List.of(
                        u64(DoicAvps.OC_SEQUENCE_NUMBER, 1L),
                        u32(DoicAvps.OC_REPORT_TYPE, DoicAvps.REPORT_HOST),
                        u32(DoicAvps.OC_REDUCTION_PERCENTAGE, 50),
                        u32(DoicAvps.OC_VALIDITY_DURATION, 60)))));
        gate.onAnswer(olrAnswer, "hss-a");
        assertEquals(50, gate.reductionPercentFor("hss-a"));

        int during = 0;
        for (int i = 0; i < 1000; i++) {
            nano[0] += 1_000_000L;
            if (gate.admit("mme-01", 5)) {
                during++;
            }
        }
        assertTrue(during >= 400 && during <= 600,
                "expected ~500 admits under 50% reduction, got " + during);
        assertTrue(during < before * 7 / 10, "reduction must materially cut admission");

        DiaMsg staleAnswer = answer(List.of(
                DiaAvp.grouped(DoicAvps.OC_SUPPORTED_FEATURES, List.of()),
                DiaAvp.grouped(DoicAvps.OC_OLR, List.of(
                        u64(DoicAvps.OC_SEQUENCE_NUMBER, 1L),
                        u32(DoicAvps.OC_REPORT_TYPE, DoicAvps.REPORT_REALM),
                        u32(DoicAvps.OC_REDUCTION_PERCENTAGE, 90),
                        u32(DoicAvps.OC_VALIDITY_DURATION, 60)))));
        gate.onAnswer(staleAnswer, "hss-a");
        assertEquals(50, gate.reductionPercentFor("hss-a"), "stale sequence must be ignored");

        wall.set(wall.get().plusSeconds(120));
        assertEquals(0, gate.reductionPercentFor("hss-a"), "expired report must lift abatement");

        int after = 0;
        for (int i = 0; i < 1000; i++) {
            nano[0] += 1_000_000L;
            if (gate.admit("mme-01", 5)) {
                after++;
            }
        }
        assertEquals(1000, after);
    }

    @Test
    void vendorTaggedSupportedFeaturesStillRecognized() {
        OlrCache olr = new OlrCache(() -> Instant.parse("2026-08-23T00:00:00Z"));
        OverloadGateImpl gate = new OverloadGateImpl(olr, new LoadCache(),
                new AdmissionController(1000, 100, 1000, 100));
        DiaMsg vendorAnswer = answer(List.of(
                new DiaAvp(DoicAvps.OC_SUPPORTED_FEATURES, 10415, false,
                        DiaAvp.TYPE_GROUPED, null, null, List.of()),
                DiaAvp.grouped(DoicAvps.OC_OLR, List.of(
                        u32(DoicAvps.OC_SEQUENCE_NUMBER, 3),
                        u32(DoicAvps.OC_REPORT_TYPE, DoicAvps.REPORT_HOST),
                        u32(DoicAvps.OC_REDUCTION_PERCENTAGE, 25),
                        u32(DoicAvps.OC_VALIDITY_DURATION, 30)))));
        gate.onAnswer(vendorAnswer, "hss-b");
        assertEquals(25, gate.reductionPercentFor("hss-b"));
    }

    @Test
    void olrWithoutSupportedFeaturesIgnored() {
        OverloadGateImpl gate = new OverloadGateImpl(new OlrCache(), new LoadCache(),
                new AdmissionController(1000, 100, 1000, 100));
        DiaMsg bareOlr = answer(List.of(
                DiaAvp.grouped(DoicAvps.OC_OLR, List.of(
                        u32(DoicAvps.OC_SEQUENCE_NUMBER, 9),
                        u32(DoicAvps.OC_REPORT_TYPE, DoicAvps.REPORT_HOST),
                        u32(DoicAvps.OC_REDUCTION_PERCENTAGE, 99),
                        u32(DoicAvps.OC_VALIDITY_DURATION, 30)))));
        gate.onAnswer(bareOlr, "hss-c");
        assertEquals(0, gate.reductionPercentFor("hss-c"));
        assertEquals(0, gate.olrCache().activeReports());
    }

    @Test
    void loadAvpHostTypeUpdatesCachePeerTypeCountsOnly() {
        OverloadGateImpl gate = new OverloadGateImpl(new OlrCache(), new LoadCache(),
                new AdmissionController(1000, 100, 1000, 100));
        DiaMsg loadAnswer = answer(List.of(
                DiaAvp.grouped(DoicAvps.LOAD, List.of(
                        u32(DoicAvps.LOAD_TYPE, LoadCache.LOAD_TYPE_HOST),
                        u32(DoicAvps.LOAD_VALUE, 77))),
                DiaAvp.grouped(DoicAvps.LOAD, List.of(
                        u32(DoicAvps.LOAD_TYPE, LoadCache.LOAD_TYPE_PEER),
                        u32(DoicAvps.LOAD_VALUE, 42)))));
        gate.onAnswer(loadAnswer, "hss-a");
        assertEquals(77, gate.loadCache().loadValue("hss-a"));
        assertEquals(1, gate.loadCache().hostUpdateCount());
        assertEquals(1, gate.loadCache().peerReportCount());
        assertNull(gate.loadCache().loadValue("unknown"));
    }

    @Test
    void tryAdmitExtensionIsCmdAware() {
        AdmissionController admission = new AdmissionController(0, 64, 0, 64, () -> 0L);
        OverloadGateImpl gate = new OverloadGateImpl(new OlrCache(), new LoadCache(), admission);
        for (int i = 0; i < 32; i++) {
            assertTrue(gate.tryAdmit("mme-01", 0, 700), "drain " + i);
        }
        assertFalse(gate.tryAdmit("mme-01", 13, 700));
        assertTrue(gate.tryAdmit("mme-01", 13, 316), "critical cmd protected");
        assertFalse(gate.admit("mme-01", 13), "seam admit stays non-critical");
    }
}
