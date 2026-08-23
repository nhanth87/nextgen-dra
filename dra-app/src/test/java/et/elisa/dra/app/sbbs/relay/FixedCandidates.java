package et.elisa.dra.app.sbbs.relay;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class FixedCandidates implements CandidateSource {

    final Map<String, List<String>> byGroup = new ConcurrentHashMap<>();
    final List<String> askedGroups = new CopyOnWriteArrayList<>();

    @Override
    public List<String> candidatesOf(String groupId, Set<String> excludePeers) {
        askedGroups.add(groupId);
        List<String> all = byGroup.getOrDefault(groupId, List.of());
        List<String> out = new java.util.ArrayList<>();
        for (String p : all) {
            if (!excludePeers.contains(p)) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }
}
