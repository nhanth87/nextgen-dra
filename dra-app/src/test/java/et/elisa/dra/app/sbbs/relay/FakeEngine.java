package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.engine.AvpOp;
import et.elisa.dra.core.engine.RouteDecision;
import et.elisa.dra.core.engine.RuleEngine;
import et.elisa.dra.core.engine.RoutingContext;
import et.elisa.dra.core.engine.ThMode;
import et.elisa.dra.core.wire.DiaMsg;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class FakeEngine implements RuleEngine {

    final AtomicReference<RouteDecision> decision = new AtomicReference<>();
    private final String selfOriginHost;

    FakeEngine(String selfOriginHost) {
        this.selfOriginHost = selfOriginHost;
        this.decision.set(new RouteDecision.Forward("mvno-hss-pool", null, true, ThMode.OFF,
                List.of(new AvpOp.AppendRouteRecord(selfOriginHost)), "hss-a"));
    }

    void forwardTo(String group, String peerId) {
        decision.set(new RouteDecision.Forward(group, null, true, ThMode.OFF,
                List.of(new AvpOp.AppendRouteRecord(selfOriginHost)), peerId));
    }

    @Override
    public RoutingContext contextFor(String ingressPeerId, DiaMsg msg) {
        String imsi = AvpOps.firstUtf8(msg, AvpCodes.USER_NAME).orElse(null);
        Map<String, String> keys = imsi != null ? Map.of(et.elisa.dra.core.bind.BindingKeys.IMSI, imsi) : Map.of();
        return new RoutingContext(ingressPeerId, msg.applicationId(), msg.commandCode(),
                msg.isRequest(), true, 0, 0, AvpOps.drmpPriority(msg), msg.destinationHost(),
                msg.destinationRealm(), msg.originHost(), msg.originRealm(), keys);
    }

    @Override
    public RouteDecision resolve(RoutingContext ctx) {
        return decision.get();
    }
}
