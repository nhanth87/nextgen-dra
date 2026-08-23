package et.elisa.dra.core.lb;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class RrLoadBalancer implements LoadBalancer {

    private final AtomicInteger wheel = new AtomicInteger();

    @Override
    public PeerHandle choose(List<PeerHandle> candidates, String preferredPeerId) {
        PeerHandle preferred = LoadBalancers.byId(candidates, preferredPeerId);
        if (preferred != null) {
            return preferred;
        }
        return candidates.get(Math.floorMod(wheel.getAndIncrement(), candidates.size()));
    }
}
