package et.elisa.dra.app.sbbs.relay;

import java.util.List;
import java.util.Set;

@FunctionalInterface
public interface CandidateSource {

    List<String> candidatesOf(String groupId, Set<String> excludePeers);
}
