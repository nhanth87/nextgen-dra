package et.elisa.dra.core.engine;

import et.elisa.dra.core.wire.DiaMsg;

public interface RuleEngine {

    RoutingContext contextFor(String ingressPeerId, DiaMsg msg);

    RouteDecision resolve(RoutingContext ctx);
}
