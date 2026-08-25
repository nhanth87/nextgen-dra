package et.elisa.dra.core.bind;

import et.elisa.dra.core.engine.RoutingContext;

import java.util.Optional;

public final class ServerInitiatedResolverImpl implements ServerInitiatedResolver {

    private final BindingStore store;

    public ServerInitiatedResolverImpl(BindingStore store) {
        this.store = store;
    }

    @Override
    public Optional<PeerRouteTarget> resolve(RoutingContext ctx) {
        Optional<PeerRouteTarget> hit = lookup(ctx, BindingKeys.IMSI);
        if (hit.isPresent()) {
            return hit;
        }
        hit = lookup(ctx, BindingKeys.MSISDN);
        if (hit.isPresent()) {
            return hit;
        }
        return lookup(ctx, BindingKeys.FRAMED_IP_APN);
    }

    private Optional<PeerRouteTarget> lookup(RoutingContext ctx, String keyType) {
        String value = ctx.key(keyType);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return store.get(keyType + ":" + value)
                .map(entry -> new PeerRouteTarget(
                        entry.groupId(),
                        entry.ingressPeerId(),
                        entry.originHost()));
    }
}
