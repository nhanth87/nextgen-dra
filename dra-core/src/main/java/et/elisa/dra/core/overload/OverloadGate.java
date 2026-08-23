package et.elisa.dra.core.overload;

import et.elisa.dra.core.wire.DiaMsg;

public interface OverloadGate {

    boolean admit(String ingressPeerId, int drmpPriority);

    void onAnswer(DiaMsg answerFromEgress, String egressPeerId);

    int reductionPercentFor(String egressPeerId);
}
