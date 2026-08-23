package et.elisa.dra.core.lb;

import java.util.List;

public interface LoadBalancer {

    PeerHandle choose(List<PeerHandle> candidates, String preferredPeerId);
}
