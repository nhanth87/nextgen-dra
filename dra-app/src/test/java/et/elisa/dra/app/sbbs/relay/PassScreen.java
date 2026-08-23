package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.screen.Screener;
import et.elisa.dra.core.wire.DiaMsg;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class PassScreen implements Screener {

    final AtomicReference<Integer> blockCode = new AtomicReference<>(null);

    @Override
    public Optional<Integer> ingressCheck(DiaMsg msg, String ingressPeerId) {
        Integer code = blockCode.get();
        return code == null ? Optional.empty() : Optional.of(code);
    }
}
