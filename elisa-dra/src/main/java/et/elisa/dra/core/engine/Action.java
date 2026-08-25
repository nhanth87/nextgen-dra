package et.elisa.dra.core.engine;

import java.util.List;

public sealed interface Action {

    record Forward(String group, StickyBinding sticky, ThMode th, boolean allowHairpin,
                   List<AvpOp> ops) implements Action {

        public Forward {
            ops = List.copyOf(ops);
        }

        public static Forward plain(String group) {
            return new Forward(group, null, ThMode.OFF, false, List.of());
        }
    }

    record Redirect(String host, String realm, long cacheSeconds) implements Action {
    }

    record Reject(int resultCode, String reason) implements Action {
    }
}
