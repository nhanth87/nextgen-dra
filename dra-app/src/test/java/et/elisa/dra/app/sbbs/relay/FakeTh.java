package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.th.TopologyHider;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FakeTh implements TopologyHider {

    static final int MARKER_OUT = 9000;
    static final int MARKER_IN = 9001;

    final Set<String> enabledGroups = ConcurrentHashMap.newKeySet();

    @Override
    public boolean enabledForGroup(String groupId) {
        return groupId != null && enabledGroups.contains(groupId);
    }

    @Override
    public DiaMsg hideOutbound(DiaMsg request, String deterministicKey) {
        return AvpOps.append(request, DiaAvp.utf8(MARKER_OUT, "out:" + (deterministicKey == null ? "-" : deterministicKey)));
    }

    @Override
    public DiaMsg restoreInbound(DiaMsg message) {
        DiaMsg stripped = AvpOps.drop(message, MARKER_OUT, 0);
        return AvpOps.append(stripped, DiaAvp.utf8(MARKER_IN, "in"));
    }
}
