package et.elisa.dra.core.lb;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

public final class LoadBalancers {

    private LoadBalancers() {
    }

    public static LoadBalancer of(LbStrategy strategy) {
        return switch (strategy) {
            case RR -> new RrLoadBalancer();
            case WEIGHTED_RR -> new WeightedRrLoadBalancer();
            case LEAST_OUTSTANDING -> new LeastOutstandingLoadBalancer();
            case LOAD_AWARE -> new LoadAwareLoadBalancer();
        };
    }

    public static PeerHandle byId(List<PeerHandle> candidates, String peerId) {
        if (peerId == null) {
            return null;
        }
        for (PeerHandle p : candidates) {
            if (p.peerId().equals(peerId)) {
                return p;
            }
        }
        return null;
    }

    public static PeerHandle smoothPick(List<PeerHandle> candidates,
                                        ToIntFunction<PeerHandle> weightFn,
                                        ConcurrentHashMap<String, Integer> currents,
                                        AtomicInteger fallbackWheel) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        int total = 0;
        for (PeerHandle p : candidates) {
            total += weightFn.applyAsInt(p);
        }
        if (total <= 0) {
            return candidates.get(Math.floorMod(fallbackWheel.getAndIncrement(), candidates.size()));
        }
        PeerHandle best = null;
        int bestWeight = Integer.MIN_VALUE;
        for (PeerHandle p : candidates) {
            int w = weightFn.applyAsInt(p);
            if (w <= 0) {
                continue;
            }
            int nw = currents.merge(p.peerId(), w, Integer::sum);
            if (nw > bestWeight) {
                bestWeight = nw;
                best = p;
            }
        }
        if (best == null) {
            return candidates.get(Math.floorMod(fallbackWheel.getAndIncrement(), candidates.size()));
        }
        currents.merge(best.peerId(), -total, Integer::sum);
        return best;
    }
}
