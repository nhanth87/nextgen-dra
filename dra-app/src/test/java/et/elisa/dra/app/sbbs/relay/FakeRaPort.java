package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.peer.DraRaPort;
import et.elisa.dra.core.peer.PeerHealth;
import et.elisa.dra.core.wire.DiaMsg;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class FakeRaPort implements DraRaPort {

    record Sent(String peerId, DiaMsg msg) {
    }

    final List<Sent> requests = new CopyOnWriteArrayList<>();
    final List<Sent> answers = new CopyOnWriteArrayList<>();
    final Set<String> failingPeers = ConcurrentHashMap.newKeySet();

    @Override
    public void sendToPeer(String peerId, DiaMsg request) {
        if (failingPeers.contains(peerId)) {
            throw new IllegalStateException("link down: " + peerId);
        }
        requests.add(new Sent(peerId, request));
    }

    @Override
    public void sendAnswerOnLink(String peerId, DiaMsg answer) {
        if (failingPeers.contains(peerId)) {
            throw new IllegalStateException("link down: " + peerId);
        }
        answers.add(new Sent(peerId, answer));
    }

    @Override
    public Map<String, PeerHealth> peersHealth() {
        return Map.of();
    }
}
