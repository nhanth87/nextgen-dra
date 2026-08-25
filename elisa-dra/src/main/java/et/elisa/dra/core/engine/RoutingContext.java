package et.elisa.dra.core.engine;

import java.util.Map;

public record RoutingContext(String ingressPeerId, int applicationId,
                             int commandCode, boolean isRequest,
                             boolean proxiable, int errorBit, int retransmitBit,
                             int drmpPriority, String destHost, String destRealm,
                             String origHost, String origRealm,
                             Map<String, String> keys) {

    public static final int DRMP_DEFAULT = 10;

    public String key(String name) {
        return keys.get(name);
    }
}
