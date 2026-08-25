package et.elisa.dra.app.ra;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public record DiameterRaConfig(List<PeerConfig> peers, String originHost, Set<String> realms,
                               long watchdogIntervalMillis, long twTimeoutMillis,
                               List<String> commandPackages) {

    public static final String LEGACY_LINK_ID = "diameter-ra";
    public static final long DEFAULT_WATCHDOG_MILLIS = 30_000L;
    public static final long DEFAULT_TW_MILLIS = 5_000L;
    public static final String COMMAND_PACKAGE_ROOT =
            "com.mobius.software.telco.protocols.diameter.impl.commands.";
    public static final List<String> DEFAULT_COMMAND_PACKAGES = List.of(
            "common", "s6a", "cxdx", "gx", "gxx", "gy", "rx", "sy", "s13",
            "s6b", "s6c", "sh", "nas", "sta", "swm", "ro", "rf",
            "creditcontrol", "eap", "t6a", "slg", "slh");

    public DiameterRaConfig {
        peers = peers == null ? List.of() : List.copyOf(peers);
        if (originHost == null || originHost.isBlank()) {
            throw new IllegalArgumentException("originHost required");
        }
        realms = realms == null ? Set.of() : Set.copyOf(realms);
        watchdogIntervalMillis = watchdogIntervalMillis < 0 ? DEFAULT_WATCHDOG_MILLIS : watchdogIntervalMillis;
        twTimeoutMillis = twTimeoutMillis <= 0 ? DEFAULT_TW_MILLIS : twTimeoutMillis;
        commandPackages = commandPackages == null || commandPackages.isEmpty()
                ? DEFAULT_COMMAND_PACKAGES : List.copyOf(commandPackages);
    }

    public DiameterRaConfig(List<PeerConfig> peers, String originHost, Set<String> realms,
                            long watchdogIntervalMillis, long twTimeoutMillis) {
        this(peers, originHost, realms, watchdogIntervalMillis, twTimeoutMillis, null);
    }

    public Optional<PeerConfig> peer(String id) {
        return peers.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public String primaryRealm() {
        return realms.stream().findFirst().orElse("");
    }

    public DiameterRaConfig withPeers(List<PeerConfig> replacement) {
        return new DiameterRaConfig(replacement, originHost, realms,
                watchdogIntervalMillis, twTimeoutMillis, commandPackages);
    }

    public static DiameterRaConfig singlePeer(String host, int port, String realm,
                                              String originHost, String productName, long vendorId,
                                              boolean tcpEnabled, boolean sctpEnabled,
                                              String peerHost, int peerPort,
                                              String destinationHost, String destinationRealm,
                                              String peerRole, long watchdogTimeoutMs) {
        var link = new PeerConfig(LEGACY_LINK_ID,
                peerHost == null || peerHost.isBlank() ? "127.0.0.1" : peerHost,
                peerPort > 0 ? peerPort : (port > 0 ? port : 3868),
                peerRole == null || peerRole.isBlank() ? PeerConfig.ROLE_CLIENT : peerRole,
                sctpEnabled && !tcpEnabled ? PeerConfig.TRANSPORT_SCTP : PeerConfig.TRANSPORT_TCP,
                Set.of(0, 1, 3, 4),
                PeerConfig.DEFAULT_GROUP,
                PeerConfig.DEFAULT_WEIGHT,
                PeerConfig.DEFAULT_MAX_OUTSTANDING);
        var realmSet = destinationRealm != null && !destinationRealm.isBlank() && !destinationRealm.equals(realm)
                ? Set.of(realm, destinationRealm)
                : Set.of(realm);
        return new DiameterRaConfig(List.of(link), originHost, realmSet,
                watchdogTimeoutMs > 0 ? watchdogTimeoutMs : DEFAULT_WATCHDOG_MILLIS, DEFAULT_TW_MILLIS);
    }
}
