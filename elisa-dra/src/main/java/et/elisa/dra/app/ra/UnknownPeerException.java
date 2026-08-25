package et.elisa.dra.app.ra;

public class UnknownPeerException extends RuntimeException {

    private final String peerId;

    public UnknownPeerException(String peerId) {
        super("unknown peer '" + peerId + "'");
        this.peerId = peerId;
    }

    public String peerId() {
        return peerId;
    }
}
