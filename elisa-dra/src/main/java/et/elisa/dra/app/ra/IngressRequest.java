package et.elisa.dra.app.ra;

import et.elisa.dra.core.wire.DiaMsg;

public record IngressRequest(DiaMsg msg, String ingressPeerId, long receivedNanos)
        implements IngressEvent {
}
