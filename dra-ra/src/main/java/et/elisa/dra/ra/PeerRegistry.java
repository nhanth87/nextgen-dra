package et.elisa.dra.ra;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import et.elisa.dra.core.peer.PeerHealth;

public final class PeerRegistry {

    private final ConcurrentMap<String, PeerConnection> links = new ConcurrentHashMap<>();
    private final long watchdogIntervalMillis;

    public PeerRegistry(DiameterRaConfig config) {
        this.watchdogIntervalMillis = config.watchdogIntervalMillis();
        config.peers().forEach(p -> links.put(p.id(), new PeerConnection(p, watchdogIntervalMillis)));
    }

    public void registerPeer(PeerConfig peer) {
        links.putIfAbsent(peer.id(), new PeerConnection(peer, watchdogIntervalMillis));
    }

    public Optional<PeerConnection> connection(String peerId) {
        return Optional.ofNullable(links.get(peerId));
    }

    public Set<String> peerIds() {
        return Set.copyOf(links.keySet());
    }

    public void onChannelUp(String peerId) {
        PeerConnection c = links.get(peerId);
        if (c != null) {
            c.channelUp();
        }
    }

    public void onCerSent(String peerId) {
        PeerConnection c = links.get(peerId);
        if (c != null) {
            c.cerSent();
        }
    }

    public void onCeaAccepted(String peerId, Set<Integer> remoteAdvertisedApps) {
        PeerConnection c = links.get(peerId);
        if (c != null) {
            c.ceaAccepted(remoteAdvertisedApps);
        }
    }

    public void onCeaRefresh(String peerId) {
        PeerConnection c = links.get(peerId);
        if (c != null) {
            c.ceaRefresh();
        }
    }

    public void onActivity(String peerId) {
        PeerConnection c = links.get(peerId);
        if (c != null) {
            c.touchActivity();
        }
    }

    public void onChannelDown(String peerId) {
        PeerConnection c = links.get(peerId);
        if (c != null) {
            c.channelDown();
        }
    }

    public void onDown(String peerId) {
        PeerConnection c = links.get(peerId);
        if (c != null) {
            c.markDown();
        }
    }

    public boolean isReady(String peerId) {
        PeerConnection c = links.get(peerId);
        return c != null && c.ready();
    }

    public Set<Integer> capabilitiesOf(String peerId) {
        PeerConnection c = links.get(peerId);
        return c == null ? Set.of() : c.advertisedApps();
    }

    public Map<String, PeerHealth> healthMap() {
        Map<String, PeerHealth> out = new ConcurrentHashMap<>();
        links.forEach((id, conn) -> out.put(id, conn.health()));
        return out;
    }

    public PeerConnection requireDeliverable(String peerId, int applicationId) {
        PeerConnection c = links.get(peerId);
        if (c == null) {
            throw new UnknownPeerException(peerId);
        }
        if (!c.ready()) {
            throw new PeerNotReadyException(peerId, readinessDetail(c));
        }
        if (!c.advertisedApps().contains(applicationId)) {
            throw new AppNotAdvertisedException(peerId, applicationId, c.advertisedApps());
        }
        return c;
    }

    public PeerConnection requireOpenLink(String peerId) {
        PeerConnection c = links.get(peerId);
        if (c == null) {
            throw new UnknownPeerException(peerId);
        }
        if (!c.ready()) {
            throw new PeerNotReadyException(peerId, readinessDetail(c));
        }
        return c;
    }

    private static String readinessDetail(PeerConnection c) {
        PeerHealth h = c.health();
        return "state=" + h.state() + " channelUp=" + h.channelUp() + " ceaOk=" + h.ceaOk()
                + " watchdogValid=" + h.watchdogValid();
    }
}
