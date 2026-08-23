package et.elisa.dra.core.tx;

import et.elisa.dra.core.common.RetryableCommands;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaySupportTest {

    @Test
    void retryableSetMatchesDefaultRetryableCommands() {
        RelaySupport support = new RelaySupport(2_000, 2);
        assertTrue(support.retryable(RetryableCommands.CMD_ULR));
        assertTrue(support.retryable(RetryableCommands.CMD_AIR));
        assertTrue(support.retryable(RetryableCommands.CMD_PUR));
        assertTrue(support.retryable(RetryableCommands.CMD_NOR));
        assertFalse(support.retryable(RetryableCommands.CMD_CCR));
        assertFalse(support.retryable(RetryableCommands.CMD_CLR));
        assertFalse(support.retryable(257));
    }

    @Test
    void deadlineIsNowPlusTw() {
        RelaySupport support = new RelaySupport(1_500, 1);
        assertEquals(10_000 + 1_500, support.deadlineFrom(10_000));
        assertEquals(1_500, support.twMillis());
    }

    @Test
    void canRetryHonorsMaxRetriesBoundary() {
        RelaySupport support = new RelaySupport(100, 2);
        assertTrue(support.canRetry(RetryableCommands.CMD_PUR, 0));
        assertTrue(support.canRetry(RetryableCommands.CMD_PUR, 1));
        assertFalse(support.canRetry(RetryableCommands.CMD_PUR, 2));
        assertFalse(support.canRetry(RetryableCommands.CMD_CCR, 0));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RelaySupport(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RelaySupport(-5, 1));
        assertThrows(IllegalArgumentException.class, () -> new RelaySupport(100, -1));
    }
}
