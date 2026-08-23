package et.elisa.dra.core.lb;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

public final class LoadAwareLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, Integer> currents = new ConcurrentHashMap<>();
    private final AtomicInteger fallbackWheel = new AtomicInteger();

    @Override
    public PeerHandle choose(List<PeerHandle> candidates, String preferredPeerId) {
        PeerHandle preferred = LoadBalancers.byId(candidates, preferredPeerId);
        if (preferred != null) {
            return preferred;
        }
        boolean anyReported = false;
        for (PeerHandle p : candidates) {
            if (p.loadValue() != null) {
                anyReported = true;
                break;
            }
        }
        ToIntFunction<PeerHandle> weightFn = anyReported
                ? p -> p.loadValue() == null ? Math.max(p.weight(), 0) : Math.max(0, 100 - p.loadValue())
                : p -> Math.max(p.weight(), 0);
        return LoadBalancers.smoothPick(candidates, weightFn, currents, fallbackWheel);
    }
}
