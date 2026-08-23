package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.overload.OverloadGate;
import et.elisa.dra.core.wire.DiaMsg;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class PassGate implements OverloadGate {

    final AtomicReference<Boolean> admitAll = new AtomicReference<>(true);
    final List<String> answeredFrom = new CopyOnWriteArrayList<>();

    @Override
    public boolean admit(String ingressPeerId, int drmpPriority) {
        return admitAll.get();
    }

    @Override
    public void onAnswer(DiaMsg answerFromEgress, String egressPeerId) {
        answeredFrom.add(egressPeerId);
    }

    @Override
    public int reductionPercentFor(String egressPeerId) {
        return 0;
    }
}
