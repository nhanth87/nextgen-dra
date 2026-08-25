package et.elisa.dra.core.overload;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionControllerTest {

    @Test
    void sustainedRateWithinTenPercentOnOneSecondWindow() {
        long[] t = {0L};
        AdmissionController c = new AdmissionController(1000, 5000, 1000, 5000, () -> t[0]);
        int admitted = 0;
        for (int i = 0; i < 1000; i++) {
            t[0] += 1_000_000L;
            if (c.tryAcquire("mme-01", 5, 700, Set.of())) {
                admitted++;
            }
        }
        assertTrue(admitted >= 900 && admitted <= 1100,
                "expected ~1000 admits at 1000/s over 1s, got " + admitted);
        assertEquals(admitted, c.admittedCount());
    }

    @Test
    void zeroRefillDeniesWhenBurstDrained() {
        AdmissionController c = new AdmissionController(0, 3, 0, 3, () -> 0L);
        assertTrue(c.tryAcquire("mme-01", 0, 700, Set.of()));
        assertTrue(c.tryAcquire("mme-01", 0, 700, Set.of()));
        assertTrue(c.tryAcquire("mme-01", 0, 700, Set.of()));
        assertFalse(c.tryAcquire("mme-01", 0, 700, Set.of()));
        assertEquals(3, c.admittedCount());
        assertEquals(1, c.throttledCount());
    }

    @Test
    void drmpHighNumberThrottledFirstAtHalfBucket() {
        AdmissionController c = new AdmissionController(0, 64, 0, 64, () -> 0L);
        for (int i = 0; i < 32; i++) {
            assertTrue(c.tryAcquire("mme-01", 0, 700, Set.of()), "drain " + i);
        }
        assertFalse(c.tryAcquire("mme-01", 15, 700, Set.of()), "PRIORITY_15 sheds first");
        assertFalse(c.tryAcquire("mme-01", 12, 700, Set.of()));
        assertFalse(c.tryAcquire("mme-01", 8, 700, Set.of()));
        assertTrue(c.tryAcquire("mme-01", 7, 700, Set.of()));
        assertFalse(c.tryAcquire("mme-01", 8, 700, Set.of()));
        assertEquals(33, c.admittedCount());
    }

    @Test
    void criticalCommandsProtectedUntilLastThreshold() {
        AdmissionController c = new AdmissionController(0, 64, 0, 64, () -> 0L);
        for (int i = 0; i < 32; i++) {
            assertTrue(c.tryAcquire("mme-01", 0, 700, Set.of()));
        }
        assertFalse(c.tryAcquire("mme-01", 13, 700, Set.of()),
                "non-critical priority 13 must shed at f=0.5");
        assertTrue(c.tryAcquire("mme-01", 13, 316, DrmpPolicy.CRITICAL_COMMANDS),
                "ULR must survive via critical protection");
        for (int i = 0; i < 28; i++) {
            assertTrue(c.tryAcquire("mme-01", 0, 316, DrmpPolicy.CRITICAL_COMMANDS),
                    "critical drain " + i);
        }
        assertFalse(c.tryAcquire("mme-01", 0, 316, DrmpPolicy.CRITICAL_COMMANDS),
                "below final threshold even critical is denied");
        assertFalse(c.tryAcquire("mme-01", 13, 318, DrmpPolicy.CRITICAL_COMMANDS),
                "AIR denied once tokens exhausted");
        assertEquals(3, c.throttledCount());
    }

    @Test
    void peerBucketsIndependentButGlobalShared() {
        AdmissionController c = new AdmissionController(0, 128, 0, 64, () -> 0L);
        for (int i = 0; i < 61; i++) {
            assertTrue(c.tryAcquire("a", 0, 700, Set.of()), "peer a drain " + i);
        }
        assertFalse(c.tryAcquire("a", 0, 700, Set.of()), "peer a drained into dead zone");
        assertTrue(c.tryAcquire("b", 0, 700, Set.of()),
                "fresh peer b unaffected by peer a depletion");
        assertFalse(c.tryAcquire("b", 15, 700, Set.of()),
                "but b still feels the shared global pool pressure");
    }

    @Test
    void invalidConstructorArgsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AdmissionController(1, 0, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionController(-1, 10, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionController(1, 10, 1, 0));
    }
}
