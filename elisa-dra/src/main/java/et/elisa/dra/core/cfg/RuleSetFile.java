package et.elisa.dra.core.cfg;

import java.util.List;
import java.util.Map;

public record RuleSetFile(int version, Self self, Map<String, GroupCfg> peerGroups,
                          List<RuleCfg> rules) {

    public record Self(String originHost, List<String> realms) {
    }

    public record GroupCfg(String lb, List<RuleSet.PeerWeight> peers, Failover failover) {

        public GroupCfg {
            peers = peers == null ? List.of() : List.copyOf(peers);
        }
    }

    public record Failover(Boolean enabled, Integer maxRetries) {
    }

    public record RuleCfg(String name, int priority, MatcherCfg when, ActionCfg then) {
    }
}
