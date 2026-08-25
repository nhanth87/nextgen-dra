package et.elisa.dra.core.lb;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class LeastOutstandingLoadBalancer implements LoadBalancer {

    private final AtomicInteger wheel = new AtomicInteger();

    @Override
    public PeerHandle choose(List<PeerHandle> candidates, String preferredPeerId) {
        PeerHandle preferred = LoadBalancers.byId(candidates, preferredPeerId);
        if (preferred != null) {
            return preferred;
        }
        int min = Integer.MAX_VALUE;
        for (PeerHandle p : candidates) {
            min = Math.min(min, p.outstanding());
        }
        List<PeerHandle> tied = new ArrayList<>(candidates.size());
        for (PeerHandle p : candidates) {
            if (p.outstanding() == min) {
                tied.add(p);
            }
        }
        return tied.get(Math.floorMod(wheel.getAndIncrement(), tied.size()));
    }
}
