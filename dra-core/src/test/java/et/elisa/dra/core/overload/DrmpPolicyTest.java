package et.elisa.dra.core.overload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrmpPolicyTest {

    @Test
    void outOfRangeFallsBackToDefault() {
        assertEquals(10, DrmpPolicy.clamp(-1));
        assertEquals(10, DrmpPolicy.clamp(16));
        assertEquals(10, DrmpPolicy.clamp(Integer.MIN_VALUE));
        assertEquals(0, DrmpPolicy.clamp(0));
        assertEquals(15, DrmpPolicy.clamp(15));
    }

    @Test
    void throttleOrderHighestNumberFirst() {
        assertEquals(0, DrmpPolicy.throttleOrder(15));
        assertEquals(15, DrmpPolicy.throttleOrder(0));
        assertTrue(DrmpPolicy.throttleOrder(12) < DrmpPolicy.throttleOrder(9));
        for (int p = DrmpPolicy.MIN_PRIORITY; p < DrmpPolicy.MAX_PRIORITY; p++) {
            assertTrue(DrmpPolicy.throttleOrder(p) > DrmpPolicy.throttleOrder(p + 1),
                    "priority " + p + " must be throttled later than " + (p + 1));
        }
    }

    @Test
    void criticalCommandsAreAuthSet() {
        assertTrue(DrmpPolicy.isCriticalCommand(316, DrmpPolicy.CRITICAL_COMMANDS));
        assertTrue(DrmpPolicy.isCriticalCommand(318, DrmpPolicy.CRITICAL_COMMANDS));
        assertTrue(DrmpPolicy.isCriticalCommand(321, DrmpPolicy.CRITICAL_COMMANDS));
        assertTrue(DrmpPolicy.isCriticalCommand(323, DrmpPolicy.CRITICAL_COMMANDS));
        assertFalse(DrmpPolicy.isCriticalCommand(317, DrmpPolicy.CRITICAL_COMMANDS));
        assertFalse(DrmpPolicy.isCriticalCommand(272, DrmpPolicy.CRITICAL_COMMANDS));
        assertFalse(DrmpPolicy.isCriticalCommand(316, null));
    }
}
