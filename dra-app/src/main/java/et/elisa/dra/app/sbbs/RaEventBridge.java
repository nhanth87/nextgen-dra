package et.elisa.dra.app.sbbs;

import com.microjainslee.api.SleeEvent;
import et.elisa.dra.core.wire.DiaMsg;

import java.util.Optional;

public interface RaEventBridge {

    record IngressRequest(String ingressPeerId, DiaMsg msg) {
    }

    record IngressAnswer(DiaMsg msg, String egressPeerId) {
    }

    Optional<IngressRequest> asRequest(SleeEvent event);

    Optional<IngressAnswer> asAnswer(SleeEvent event);
}
