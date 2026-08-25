package et.elisa.dra.core.lb;

public record PeerHandle(String peerId, int weight, int outstanding,
                         Integer loadValue) {

    public boolean healthy() {
        return loadValue == null || loadValue > 0;
    }
}
