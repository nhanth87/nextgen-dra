package et.elisa.dra.ra;

import java.util.Set;

public record PeerConfig(String id, String host, int port, Integer listenPort,
                         String remoteIdentityHost, String remoteIdentityRealm,
                         String role, String transport, Set<Integer> advertisedApps,
                         String group, int weight, int maxOutstanding) {

    public static final String ROLE_CLIENT = "CLIENT";
    public static final String ROLE_SERVER = "SERVER";
    public static final String TRANSPORT_TCP = "TCP";
    public static final String TRANSPORT_SCTP = "SCTP";
    public static final String DEFAULT_GROUP = "default";
    public static final int DEFAULT_WEIGHT = 1;
    public static final int DEFAULT_MAX_OUTSTANDING = 2000;

    public PeerConfig {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("peer id required");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("peer host required for " + id);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("peer port out of range for " + id + ": " + port);
        }
        if (listenPort != null && (listenPort < 1 || listenPort > 65535)) {
            throw new IllegalArgumentException("peer listenPort out of range for " + id + ": " + listenPort);
        }
        role = normalizeChoice(role, ROLE_CLIENT, ROLE_SERVER, "role");
        transport = normalizeChoice(transport, TRANSPORT_TCP, TRANSPORT_SCTP, "transport");
        advertisedApps = advertisedApps == null ? Set.of() : Set.copyOf(advertisedApps);
        group = group == null || group.isBlank() ? DEFAULT_GROUP : group.trim();
        weight = weight <= 0 ? DEFAULT_WEIGHT : weight;
        maxOutstanding = maxOutstanding <= 0 ? DEFAULT_MAX_OUTSTANDING : maxOutstanding;
    }

    public PeerConfig(String id, String host, int port, String role,
                      String transport, Set<Integer> advertisedApps,
                      String group, int weight, int maxOutstanding) {
        this(id, host, port, null, null, null, role, transport, advertisedApps,
                group, weight, maxOutstanding);
    }

    public PeerConfig(String id, String host, int port, Integer listenPort,
                      String role, String transport, Set<Integer> advertisedApps,
                      String group, int weight, int maxOutstanding) {
        this(id, host, port, listenPort, null, null, role, transport, advertisedApps,
                group, weight, maxOutstanding);
    }

    public String effectiveRemoteIdentityHost() {
        return remoteIdentityHost == null || remoteIdentityHost.isBlank() ? id : remoteIdentityHost;
    }

    public String effectiveRemoteIdentityRealm(String fallbackRealm) {
        return remoteIdentityRealm == null || remoteIdentityRealm.isBlank()
                ? fallbackRealm : remoteIdentityRealm;
    }

    public boolean isServer() {
        return ROLE_SERVER.equals(role);
    }

    public boolean isSctp() {
        return TRANSPORT_SCTP.equals(transport);
    }

    public int effectiveListenPort() {
        return isServer() ? (listenPort != null ? listenPort : port) : 0;
    }

    public boolean acceptsApp(int applicationId) {
        return advertisedApps.contains(applicationId);
    }

    private static String normalizeChoice(String value, String a, String b, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
        String upper = value.trim().toUpperCase();
        if (!upper.equals(a) && !upper.equals(b)) {
            throw new IllegalArgumentException(field + " must be " + a + " or " + b + ": " + value);
        }
        return upper;
    }
}
