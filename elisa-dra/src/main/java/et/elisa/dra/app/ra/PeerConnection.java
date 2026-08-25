package et.elisa.dra.app.ra;

import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

import et.elisa.dra.core.peer.PeerHealth;

final class PeerConnection {

    private final PeerConfig config;
    private final long watchdogIntervalNanos;
    private volatile boolean channelUp;
    private volatile boolean ceaOk;
    private volatile PeerState state = PeerState.IDLE;
    private volatile Set<Integer> advertisedApps = Set.of();
    private volatile long lastActivityNanos = System.nanoTime();
    private final LongAdder outstanding = new LongAdder();

    PeerConnection(PeerConfig config, long watchdogIntervalMillis) {
        this.config = config;
        this.watchdogIntervalNanos = watchdogIntervalMillis > 0
                ? watchdogIntervalMillis * 1_000_000L
                : 0L;
    }

    PeerConfig config() {
        return config;
    }

    synchronized void channelUp() {
        if (channelUp) {
            touch();
            return;
        }
        channelUp = true;
        ceaOk = false;
        state = PeerState.CER_SENT;
        touch();
    }

    synchronized void cerSent() {
        touch();
        if (channelUp) {
            state = PeerState.CER_SENT;
        }
    }

    synchronized void ceaAccepted(Set<Integer> remoteAdvertisedApps) {
        ceaOk = true;
        if (remoteAdvertisedApps != null && !remoteAdvertisedApps.isEmpty()) {
            advertisedApps = Set.copyOf(remoteAdvertisedApps);
        }
        touch();
        if (channelUp) {
            state = PeerState.OPEN;
        }
    }

    synchronized void ceaRefresh() {
        ceaOk = true;
        touch();
    }

    synchronized void channelDown() {
        touch();
        channelUp = false;
        ceaOk = false;
        if (state != PeerState.IDLE) {
            state = PeerState.DOWN;
        }
    }

    synchronized void markDown() {
        channelDown();
    }

    void touchActivity() {
        touch();
    }

    boolean watchdogValid(long nowNanos) {
        return watchdogIntervalNanos <= 0L || (nowNanos - lastActivityNanos) <= watchdogIntervalNanos;
    }

    boolean ready() {
        long now = System.nanoTime();
        return channelUp && ceaOk && watchdogValid(now);
    }

    Set<Integer> advertisedApps() {
        return advertisedApps;
    }

    void incOutstanding() {
        outstanding.increment();
    }

    void decOutstanding() {
        outstanding.decrement();
        if (outstanding.sum() < 0) {
            outstanding.reset();
        }
    }

    int outstandingCount() {
        return (int) Math.min(Integer.MAX_VALUE, outstanding.sum());
    }

    boolean saturated() {
        return config.maxOutstanding() > 0 && outstanding.sum() >= config.maxOutstanding();
    }

    PeerHealth health() {
        long now = System.nanoTime();
        return new PeerHealth(config.id(), channelUp, ceaOk, watchdogValid(now),
                outstandingCount(), advertisedApps, state.name());
    }

    private void touch() {
        lastActivityNanos = System.nanoTime();
    }
}
