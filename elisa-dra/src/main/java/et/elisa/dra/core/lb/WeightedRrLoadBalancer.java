package et.elisa.dra.core.lb;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

public final class WeightedRrLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, Integer> currents = new ConcurrentHashMap<>();
    private final AtomicInteger fallbackWheel = new AtomicInteger();

    @Override
    public PeerHandle choose(List<PeerHandle> candidates, String preferredPeerId) {
        PeerHandle preferred = LoadBalancers.byId(candidates, preferredPeerId);
        if (preferred != null) {
            return preferred;
        }
        return LoadBalancers.smoothPick(candidates, p -> Math.max(p.weight(), 0), currents, fallbackWheel);
    }
}
