package et.elisa.dra.app.ra;

import java.util.Set;

public final class AppNotAdvertisedException extends PeerNotReadyException {

    private final int applicationId;
    private final Set<Integer> advertisedApps;

    public AppNotAdvertisedException(String peerId, int applicationId, Set<Integer> advertisedApps) {
        super(peerId, "application " + applicationId + " not advertised (advertised=" + advertisedApps + ")");
        this.applicationId = applicationId;
        this.advertisedApps = Set.copyOf(advertisedApps);
    }

    public int applicationId() {
        return applicationId;
    }

    public Set<Integer> advertisedApps() {
        return advertisedApps;
    }
}
