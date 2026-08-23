package et.elisa.dra.ra;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import et.elisa.dra.core.peer.DraRaPort;
import et.elisa.dra.core.peer.PeerHealth;
import et.elisa.dra.core.wire.DiaMsg;

public final class SimulatedPeerFabric implements DraRaPort {

    private final DiameterRaConfig config;
    private final PeerRegistry registry;
    private final ConcurrentMap<String, ConcurrentLinkedQueue<DiaMsg>> sentBoxes = new ConcurrentHashMap<>();
    private volatile IngressListener listener;
    private volatile Function<IngressRequest, DiaMsg> autoAnswer;

    public SimulatedPeerFabric(DiameterRaConfig config) {
        Objects.requireNonNull(config, "config");
        this.config = config;
        this.registry = new PeerRegistry(config);
        config.peers().forEach(p -> sentBoxes.put(p.id(), new ConcurrentLinkedQueue<>()));
    }

    public void setIngressListener(IngressListener l) {
        this.listener = l;
    }

    public void setAutoAnswer(Function<IngressRequest, DiaMsg> answerer) {
        this.autoAnswer = answerer;
    }

    public PeerRegistry registry() {
        return registry;
    }

    public DiameterRaConfig config() {
        return config;
    }

    public void peerUp(String peerId) {
        registry.onChannelUp(peerId);
        registry.onCeaAccepted(peerId, registry.connection(peerId)
                .map(c -> c.config().advertisedApps()).orElse(java.util.Set.of()));
    }

    public void peerCea(String peerId, java.util.Set<Integer> advertisedApps) {
        registry.onChannelUp(peerId);
        registry.onCeaAccepted(peerId, advertisedApps);
    }

    public void peerDown(String peerId) {
        registry.onChannelDown(peerId);
    }

    public void inboundRequest(String peerId, DiaMsg request) {
        Objects.requireNonNull(request, "request");
        registry.onActivity(peerId);
        IngressListener l = listener;
        var event = new IngressRequest(request, peerId, System.nanoTime());
        if (l != null) {
            l.onIngress(event);
        }
        Function<IngressRequest, DiaMsg> answerer = autoAnswer;
        if (request.isRequest() && answerer != null) {
            DiaMsg answer = answerer.apply((IngressRequest) event);
            if (answer != null) {
                injectAnswer(peerId, answer);
            }
        }
    }

    public void injectAnswer(String egressPeerId, DiaMsg answer) {
        registry.onActivity(egressPeerId);
        registry.connection(egressPeerId).ifPresent(PeerConnection::decOutstanding);
        IngressListener l = listener;
        if (l != null) {
            l.onIngress(new IngressAnswer(answer, egressPeerId, System.nanoTime()));
        }
    }

    @Override
    public void sendToPeer(String peerId, DiaMsg request) {
        Objects.requireNonNull(request, "request");
        if (!request.isRequest()) {
            throw new IllegalArgumentException("sendToPeer requires a request message");
        }
        PeerConnection conn = registry.requireDeliverable(peerId, request.applicationId());
        if (conn.saturated()) {
            throw new PeerNotReadyException(peerId,
                    "outstanding >= maxOutstanding " + conn.config().maxOutstanding());
        }
        box(peerId).offer(request);
        conn.incOutstanding();
    }

    @Override
    public void sendAnswerOnLink(String peerId, DiaMsg answer) {
        Objects.requireNonNull(answer, "answer");
        if (answer.isRequest()) {
            throw new IllegalArgumentException("sendAnswerOnLink requires an answer message");
        }
        registry.requireOpenLink(peerId);
        box(peerId).offer(answer);
    }

    @Override
    public ConcurrentHashMap<String, PeerHealth> peersHealth() {
        return new ConcurrentHashMap<>(registry.healthMap());
    }

    public DiaMsg takeSent(String peerId) {
        return box(peerId).poll();
    }

    public int sentCount(String peerId) {
        return box(peerId).size();
    }

    public void clearSent(String peerId) {
        box(peerId).clear();
    }

    private ConcurrentLinkedQueue<DiaMsg> box(String peerId) {
        return sentBoxes.computeIfAbsent(peerId, id -> new ConcurrentLinkedQueue<>());
    }
}
