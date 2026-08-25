package et.elisa.dra.core.peer;

import et.elisa.dra.core.wire.DiaMsg;

import java.util.Map;

public interface DraRaPort {

    void sendToPeer(String peerId, DiaMsg request);

    void sendAnswerOnLink(String peerId, DiaMsg answer);

    Map<String, PeerHealth> peersHealth();
}
