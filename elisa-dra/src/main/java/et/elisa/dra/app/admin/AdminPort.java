package et.elisa.dra.app.admin;

import et.elisa.dra.core.peer.PeerHealth;

import java.util.List;
import java.util.Map;

public interface AdminPort {

    boolean live();

    Map<String, PeerHealth> peersHealth();

    long bindingsCount();

    boolean enablePeer(String peerId);

    boolean disablePeer(String peerId);

    /** Live relay telemetry (relay-core counters + gauges). Never null. */
    default TelemetryPort telemetry() {
        return TelemetryPort.NOOP;
    }

    /** Most recent binding entries for the dashboard (bounded by limit). */
    default List<Map<String, Object>> bindingsSample(int limit) {
        return List.of();
    }

    /** Effective runtime configuration actually wired into the relay plane. */
    default Map<String, Object> runtimeConfig() {
        return Map.of();
    }

    /** Peer ids administratively drained (excluded from new forwarding). */
    default java.util.Set<String> disabledPeers() {
        return java.util.Set.of();
    }

    AdminPort NOOP = new AdminPort() {

        @Override
        public boolean live() {
            return false;
        }

        @Override
        public Map<String, PeerHealth> peersHealth() {
            return Map.of();
        }

        @Override
        public long bindingsCount() {
            return 0;
        }

        @Override
        public boolean enablePeer(String peerId) {
            return false;
        }

        @Override
        public boolean disablePeer(String peerId) {
            return false;
        }

        @Override
        public TelemetryPort telemetry() {
            return TelemetryPort.NOOP;
        }

        @Override
        public List<Map<String, Object>> bindingsSample(int limit) {
            return List.of();
        }

        @Override
        public Map<String, Object> runtimeConfig() {
            return Map.of();
        }
    };
}
