package et.elisa.dra.core.cfg;

import et.elisa.dra.core.engine.Rule;
import et.elisa.dra.core.lb.LbStrategy;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RuleSet(int version, String selfOriginHost, Set<String> selfRealms,
                      List<Rule> rules, Map<String, GroupSpec> groups) {

    public record GroupSpec(String name, LbStrategy strategy, List<PeerWeight> peers,
                            boolean failoverEnabled, int maxRetries) {
    }

    public record PeerWeight(String id, int weight) {
    }

    public RuleSet {
        selfRealms = selfRealms == null ? Set.of() : Set.copyOf(selfRealms);
        rules = rules == null ? List.of() : rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority))
                .toList();
        groups = groups == null ? Map.of() : Map.copyOf(groups);
    }
}
