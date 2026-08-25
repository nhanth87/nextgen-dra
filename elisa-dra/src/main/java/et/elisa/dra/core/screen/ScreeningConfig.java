package et.elisa.dra.core.screen;

import java.util.Map;
import java.util.Set;

public record ScreeningConfig(Map<String, PeeringRules> peerings) {

    public record PeeringRules(Set<Integer> appIds, Set<Integer> cmdCodes,
                               Set<String> realmSuffixes, Set<IpV4Cidr> ipPrefixes,
                               boolean trustedNoProxy) {

        public static final PeeringRules ALLOW_ALL =
                new PeeringRules(Set.of(), Set.of(), Set.of(), Set.of(), false);

        public PeeringRules {
            appIds = Set.copyOf(appIds);
            cmdCodes = Set.copyOf(cmdCodes);
            realmSuffixes = Set.copyOf(realmSuffixes);
            ipPrefixes = Set.copyOf(ipPrefixes);
        }
    }

    public ScreeningConfig {
        peerings = Map.copyOf(peerings);
    }

    public static ScreeningConfig of(Map<String, PeeringRules> map) {
        return new ScreeningConfig(map);
    }

    public PeeringRules forPeer(String peerId) {
        return peerings.getOrDefault(peerId, PeeringRules.ALLOW_ALL);
    }
}
