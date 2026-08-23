package et.elisa.dra.core.lb;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class GroupRuntime {

    private final String groupId;
    private final LbStrategy strategy;
    private final boolean failoverEnabled;
    private final int maxRetries;
    private final LoadBalancer balancer;
    private final AtomicReference<List<PeerHandle>> candidates = new AtomicReference<>(List.of());

    public GroupRuntime(String groupId, LbStrategy strategy, boolean failoverEnabled, int maxRetries) {
        this.groupId = groupId;
        this.strategy = strategy;
        this.failoverEnabled = failoverEnabled;
        this.maxRetries = maxRetries;
        this.balancer = LoadBalancers.of(strategy);
    }

    public void updateCandidates(List<PeerHandle> handles) {
        candidates.set(List.copyOf(handles));
    }

    public List<PeerHandle> candidates() {
        return candidates.get();
    }

    public PeerHandle choose(List<PeerHandle> eligible, String preferredPeerId) {
        return balancer.choose(eligible, preferredPeerId);
    }

    public boolean sameShape(LbStrategy s, boolean failover, int retries) {
        return strategy == s && failoverEnabled == failover && maxRetries == retries;
    }

    public String groupId() {
        return groupId;
    }

    public LbStrategy strategy() {
        return strategy;
    }

    public boolean failoverEnabled() {
        return failoverEnabled;
    }

    public int maxRetries() {
        return maxRetries;
    }
}
