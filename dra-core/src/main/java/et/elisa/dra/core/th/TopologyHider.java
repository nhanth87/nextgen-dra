package et.elisa.dra.core.th;

import et.elisa.dra.core.wire.DiaMsg;

public interface TopologyHider {

    boolean enabledForGroup(String groupId);

    DiaMsg hideOutbound(DiaMsg request, String deterministicKey);

    DiaMsg restoreInbound(DiaMsg message);
}
