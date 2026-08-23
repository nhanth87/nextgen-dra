package et.elisa.dra.core.screen;

import et.elisa.dra.core.wire.DiaMsg;

import java.util.Optional;

public interface Screener {

    Optional<Integer> ingressCheck(DiaMsg msg, String ingressPeerId);
}
