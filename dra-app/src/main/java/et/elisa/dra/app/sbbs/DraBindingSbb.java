package et.elisa.dra.app.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import et.elisa.dra.app.sbbs.relay.RelayCore;

import java.util.function.LongSupplier;

public final class DraBindingSbb implements Sbb, SleeEventHandler {

    private final RelayCore core;
    private final RaEventBridge bridge;
    private final LongSupplier tickClock;

    public DraBindingSbb(RelayCore core, RaEventBridge bridge, LongSupplier tickClock) {
        this.core = core;
        this.bridge = bridge;
        this.tickClock = tickClock;
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        bridge.asRequest(event).ifPresent(r -> core.serverInitiated(r.ingressPeerId(), r.msg()));
        bridge.asAnswer(event).ifPresent(a -> core.onAnswer(a.msg(), a.egressPeerId()));
    }

    public void onSweepTick() {
        core.sweep(tickClock.getAsLong());
    }
}
