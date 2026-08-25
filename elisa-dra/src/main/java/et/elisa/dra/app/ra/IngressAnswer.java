package et.elisa.dra.app.ra;

import et.elisa.dra.core.wire.DiaMsg;

public record IngressAnswer(DiaMsg msg, String egressPeerId, long receivedNanos)
        implements IngressEvent {
}
