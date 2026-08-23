package et.elisa.dra.app.admin;

import et.elisa.dra.core.peer.PeerHealth;

import java.util.Map;

public interface AdminPort {

    boolean live();

    Map<String, PeerHealth> peersHealth();

    long bindingsCount();

    boolean enablePeer(String peerId);

    boolean disablePeer(String peerId);

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
    };
}
