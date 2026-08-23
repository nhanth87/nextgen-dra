package et.elisa.dra.app.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import et.elisa.dra.app.sbbs.relay.RelayCore;
import et.elisa.dra.core.overload.OverloadGate;

public final class DraOverloadSbb implements Sbb, SleeEventHandler {

    private final RelayCore core;
    private final RaEventBridge bridge;
    private final OverloadGate gate;

    public DraOverloadSbb(RelayCore core, RaEventBridge bridge, OverloadGate gate) {
        this.core = core;
        this.bridge = bridge;
        this.gate = gate;
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        bridge.asRequest(event).ifPresent(r -> core.onRequest(r.ingressPeerId(), r.msg()));
        bridge.asAnswer(event).ifPresent(a -> core.onAnswer(a.msg(), a.egressPeerId()));
    }

    public OverloadGate gate() {
        return gate;
    }
}
