package et.elisa.dra.core.bind;

public record PeerRouteTarget(String groupId, String preferredPeerId,
                              String destHostRewrite) {
}
