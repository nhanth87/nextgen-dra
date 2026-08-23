package et.elisa.dra.core.th;

import java.util.Set;

public record ThConfig(String internalDomainSuffix, String pseudoPrefix,
                       int pseudoCount, boolean fullEdge,
                       Set<String> thGroups) {

    public ThConfig {
        if (internalDomainSuffix == null || internalDomainSuffix.isBlank()) {
            throw new IllegalArgumentException("internalDomainSuffix required");
        }
        if (pseudoPrefix == null || pseudoPrefix.isBlank()) {
            throw new IllegalArgumentException("pseudoPrefix required");
        }
        if (pseudoCount <= 0) {
            throw new IllegalArgumentException("pseudoCount must be positive");
        }
        thGroups = Set.copyOf(thGroups);
    }

    public boolean enabledFor(String groupId) {
        return thGroups.contains(groupId);
    }
}
