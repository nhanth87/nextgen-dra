package et.elisa.dra.ra;

public class PeerNotReadyException extends RuntimeException {

    private final String peerId;

    public PeerNotReadyException(String peerId, String reason) {
        super("peer '" + peerId + "' not ready: " + reason);
        this.peerId = peerId;
    }

    public String peerId() {
        return peerId;
    }
}
