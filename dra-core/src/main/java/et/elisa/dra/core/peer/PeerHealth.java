package et.elisa.dra.core.peer;

import java.util.Set;

public record PeerHealth(String peerId, boolean channelUp, boolean ceaOk,
                         boolean watchdogValid, int outstanding,
                         Set<Integer> advertisedApps, String state) {

    public boolean ready() {
        return channelUp && ceaOk && watchdogValid;
    }
}
