package et.elisa.dra.app.ra;

import com.microjainslee.api.OutboundCommand;
import et.elisa.dra.core.wire.DiaMsg;

public record DraSendCommand(String peerId, DiaMsg msg) implements OutboundCommand {
}
