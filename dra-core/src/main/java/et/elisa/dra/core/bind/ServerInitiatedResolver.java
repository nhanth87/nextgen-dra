package et.elisa.dra.core.bind;

import et.elisa.dra.core.engine.RoutingContext;

import java.util.Optional;

public interface ServerInitiatedResolver {

    Optional<PeerRouteTarget> resolve(RoutingContext ctx);
}
