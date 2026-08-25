package et.elisa.dra.core.tx;

import et.elisa.dra.core.common.RetryableCommands;

public final class RelaySupport {

    private final long twMillis;
    private final int maxRetries;

    public RelaySupport(long twMillis, int maxRetries) {
        if (twMillis < 1) {
            throw new IllegalArgumentException("twMillis must be >= 1");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        this.twMillis = twMillis;
        this.maxRetries = maxRetries;
    }

    public long twMillis() {
        return twMillis;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public long deadlineFrom(long nowMillis) {
        return nowMillis + twMillis;
    }

    public boolean retryable(int commandCode) {
        return RetryableCommands.DEFAULT_RETRYABLE.contains(commandCode);
    }

    public boolean canRetry(int commandCode, int retryCount) {
        return retryable(commandCode) && retryCount < maxRetries;
    }
}
