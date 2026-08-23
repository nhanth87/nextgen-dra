package et.elisa.dra.app.ra;

import com.microjainslee.api.SleeEvent;
import et.elisa.dra.core.wire.DiaMsg;

public record DraAnswerEvent(DiaMsg msg, String peerId) implements SleeEvent {
}
