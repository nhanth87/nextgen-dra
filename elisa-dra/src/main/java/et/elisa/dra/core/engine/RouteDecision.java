package et.elisa.dra.core.engine;

public sealed interface RouteDecision {

    record Forward(String group, StickyBinding sticky, boolean failoverEnabled,
                   ThMode th, java.util.List<AvpOp> ops,
                   String preferredPeerId) implements RouteDecision {

        public static Forward plain(String group) {
            return new Forward(group, null, true, ThMode.OFF,
                    java.util.List.of(), null);
        }
    }

    record Redirect(String host, String realm, long cacheSeconds) implements RouteDecision {
    }

    record Reject(int resultCode, String reason) implements RouteDecision {
    }
}
