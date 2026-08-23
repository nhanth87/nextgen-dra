package et.elisa.dra.app.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import et.elisa.dra.app.sbbs.relay.RelayCore;

public final class DraRelaySbb implements Sbb, SleeEventHandler {

    private final RelayCore core;
    private final RaEventBridge bridge;

    public DraRelaySbb(RelayCore core, RaEventBridge bridge) {
        this.core = core;
        this.bridge = bridge;
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        bridge.asRequest(event).ifPresent(r -> core.onRequest(r.ingressPeerId(), r.msg()));
        bridge.asAnswer(event).ifPresent(a -> core.onAnswer(a.msg(), a.egressPeerId()));
    }
}
