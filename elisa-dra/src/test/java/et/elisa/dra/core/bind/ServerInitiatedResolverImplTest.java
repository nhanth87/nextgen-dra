package et.elisa.dra.core.bind;

import et.elisa.dra.core.engine.RoutingContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerInitiatedResolverImplTest {

    private final InMemoryBindingStore store = new InMemoryBindingStore();
    private final ServerInitiatedResolverImpl resolver = new ServerInitiatedResolverImpl(store);

    private static RoutingContext ctx(Map<String, String> keys) {
        return new RoutingContext("mme-01-link", 16777216, 316, true, true, 0, 0,
                RoutingContext.DRMP_DEFAULT, null, "epc.mnc01.mcc452.3gppnetwork.org",
                "hss-a.epc.mnc01.mcc452.3gppnetwork.org", "epc.mnc01.mcc452.3gppnetwork.org",
                keys);
    }

    private static BindingEntry binding(String key) {
        Instant now = Instant.now();
        return new BindingEntry(key, "mvno-hss-pool", "hss-a", "MME-01.epc.mnc01.mcc452.3gppnetwork.org",
                "epc.mnc01.mcc452.3gppnetwork.org", "mme-01-link", now, now.plus(Duration.ofHours(24)));
    }

    @Test
    void resolvesFromImsiBinding() {
        store.put(binding("IMSI:452040100000001"));
        Optional<PeerRouteTarget> target = resolver.resolve(ctx(Map.of(
                BindingKeys.IMSI, "452040100000001")));
        assertTrue(target.isPresent());
        PeerRouteTarget t = target.orElseThrow();
        assertEquals("mvno-hss-pool", t.groupId());
        assertEquals("mme-01-link", t.preferredPeerId());
        assertEquals("MME-01.epc.mnc01.mcc452.3gppnetwork.org", t.destHostRewrite());
    }

    @Test
    void fallsBackToMsisdnWhenImsiAbsent() {
        store.put(binding("MSISDN:84123456789"));
        Optional<PeerRouteTarget> target = resolver.resolve(ctx(Map.of(
                BindingKeys.IMSI, "452040199999999",
                BindingKeys.MSISDN, "84123456789")));
        assertTrue(target.isPresent());
        assertEquals("mme-01-link", target.orElseThrow().preferredPeerId());
    }

    @Test
    void emptyWhenNoBindingAtAll() {
        Optional<PeerRouteTarget> target = resolver.resolve(ctx(Map.of(
                BindingKeys.IMSI, "452040100000002")));
        assertTrue(target.isEmpty(), "caller must fail-close with 3002");
    }

    @Test
    void emptyWhenNoKeysExtracted() {
        assertTrue(resolver.resolve(ctx(Map.of())).isEmpty());
    }

    @Test
    void expiredImsiEntryDoesNotResolve() {
        Instant now = Instant.now();
        store.put(new BindingEntry("IMSI:452040100000003", "mvno-hss-pool", "hss-a",
                "MME-01", "realm", "mme-01-link", now.minusSeconds(60), now.minusSeconds(1)));
        assertTrue(resolver.resolve(ctx(Map.of(BindingKeys.IMSI, "452040100000003"))).isEmpty(),
                "expired binding must not route (lazy expiry)");
    }

    @Test
    void blankKeyTreatedAsAbsent() {
        store.put(binding("IMSI:"));
        assertTrue(resolver.resolve(ctx(Map.of(BindingKeys.IMSI, ""))).isEmpty());
    }

    @Test
    void framedIpApnFallbackResolvesWhenImsiAndMsisdnMiss() {
        store.put(binding("FRAMED_IP_APN:10.20.30.40+ims.mnc01.mcc452.3gppnetwork.org"));
        Optional<PeerRouteTarget> target = resolver.resolve(ctx(Map.of(
                BindingKeys.FRAMED_IP_APN, "10.20.30.40+ims.mnc01.mcc452.3gppnetwork.org")));
        assertTrue(target.isPresent(), "PCC binding TS 29.213 must resolve by framed-ip+apn");
        assertEquals("mme-01-link", target.orElseThrow().preferredPeerId());
        assertEquals("MME-01.epc.mnc01.mcc452.3gppnetwork.org", target.orElseThrow().destHostRewrite());
    }

    private static BindingEntry bindingFor(String key, String ingressLink) {
        Instant now = Instant.now();
        return new BindingEntry(key, "mvno-hss-pool", "hss-a", "MME-01.epc.mnc01.mcc452.3gppnetwork.org",
                "epc.mnc01.mcc452.3gppnetwork.org", ingressLink, now, now.plus(Duration.ofHours(24)));
    }

    @Test
    void imsiWinsOverMsisdnAndFramedIpWhenAllBound() {
        store.put(bindingFor("IMSI:452040100000010", "mme-imsi-link"));
        store.put(bindingFor("MSISDN:84123456789", "mme-msisdn-link"));
        store.put(bindingFor("FRAMED_IP_APN:10.0.0.9", "pgw-gxcu-link"));
        Optional<PeerRouteTarget> target = resolver.resolve(ctx(Map.of(
                BindingKeys.IMSI, "452040100000010",
                BindingKeys.MSISDN, "84123456789",
                BindingKeys.FRAMED_IP_APN, "10.0.0.9")));
        assertEquals("mme-imsi-link", target.orElseThrow().preferredPeerId());
    }

    @Test
    void msisdnWinsOverFramedIpWhenImsiUnbound() {
        store.put(bindingFor("MSISDN:84123456789", "mme-msisdn-link"));
        store.put(bindingFor("FRAMED_IP_APN:10.0.0.9", "pgw-gxcu-link"));
        Optional<PeerRouteTarget> target = resolver.resolve(ctx(Map.of(
                BindingKeys.MSISDN, "84123456789",
                BindingKeys.FRAMED_IP_APN, "10.0.0.9")));
        assertEquals("mme-msisdn-link", target.orElseThrow().preferredPeerId());
    }

    @Test
    void destHostPresentWithoutBindingStillEmptyCallerRoutesByHost() {
        RoutingContext ctx = new RoutingContext("hss-a-link", 16777216, 316, true, true, 0, 0,
                RoutingContext.DRMP_DEFAULT, "MME-01.epc.mnc01.mcc452.3gppnetwork.org",
                "epc.mnc01.mcc452.3gppnetwork.org",
                "hss-a.epc.mnc01.mcc452.3gppnetwork.org", "epc.mnc01.mcc452.3gppnetwork.org",
                Map.of(BindingKeys.IMSI, "452040100000099"));
        assertTrue(resolver.resolve(ctx).isEmpty(),
                "no binding + explicit Dest-Host: caller routes by host, no guess");
    }
}
