package et.elisa.dra.core.screen;

import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.common.DraResultCodes;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

public final class ScreeningServiceImpl implements Screener {

    private final ScreeningConfig config;
    private final LongAdder appRejected = new LongAdder();
    private final LongAdder cmdRejected = new LongAdder();
    private final LongAdder realmRejected = new LongAdder();
    private final LongAdder foreignProxyState = new LongAdder();

    public ScreeningServiceImpl(ScreeningConfig config) {
        this.config = config;
    }

    @Override
    public Optional<Integer> ingressCheck(DiaMsg msg, String ingressPeerId) {
        ScreeningConfig.PeeringRules rules = config.forPeer(ingressPeerId);
        if (!rules.appIds().isEmpty() && !rules.appIds().contains(msg.applicationId())) {
            appRejected.increment();
            return Optional.of(DraResultCodes.APPLICATION_UNSUPPORTED);
        }
        if (!rules.cmdCodes().isEmpty() && !rules.cmdCodes().contains(msg.commandCode())) {
            cmdRejected.increment();
            return Optional.of(DraResultCodes.UNABLE_TO_DELIVER);
        }
        if (!rules.realmSuffixes().isEmpty()
                && !matchesAnyRealmSuffix(msg.originRealm(), rules.realmSuffixes())) {
            realmRejected.increment();
            return Optional.of(DraResultCodes.UNABLE_TO_DELIVER);
        }
        if (msg.isRequest() && rules.trustedNoProxy() && hasProxyState(msg)) {
            foreignProxyState.increment();
        }
        return Optional.empty();
    }

    public boolean checkIp(String peerId, String address) {
        ScreeningConfig.PeeringRules rules = config.forPeer(peerId);
        if (rules.ipPrefixes().isEmpty()) {
            return true;
        }
        Integer ip = IpV4Cidr.parseIp(address);
        if (ip == null) {
            return false;
        }
        for (IpV4Cidr p : rules.ipPrefixes()) {
            if (p.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    public long appRejectCount() {
        return appRejected.sum();
    }

    public long cmdRejectCount() {
        return cmdRejected.sum();
    }

    public long realmRejectCount() {
        return realmRejected.sum();
    }

    public long foreignProxyStateCount() {
        return foreignProxyState.sum();
    }

    static boolean matchesAnyRealmSuffix(String originRealm, java.util.Set<String> suffixes) {
        if (originRealm == null || originRealm.isEmpty()) {
            return false;
        }
        String realm = originRealm.toLowerCase(Locale.ROOT);
        for (String s : suffixes) {
            String suffix = s == null ? "" : s.toLowerCase(Locale.ROOT);
            if (suffix.isEmpty()) {
                return true;
            }
            String anchored = suffix.startsWith(".") ? suffix : "." + suffix;
            if (realm.equals(suffix) || realm.endsWith(anchored)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProxyState(DiaMsg msg) {
        List<DiaAvp> avps = msg.avps() == null ? List.of() : msg.avps();
        return avps.stream().anyMatch(a -> a.code() == AvpCodes.PROXY_STATE);
    }
}
