package et.elisa.dra.core.screen;

import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreeningServiceImplTest {

    private static final int S6A = 16777251;

    private static DiaMsg request(int appId, int cmdCode, String originRealm, List<DiaAvp> avps) {
        return new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, cmdCode, appId,
                11L, 22L, "sess", "mme-01.epc.mnc01.mcc452.3gppnetwork.org",
                originRealm, "hss-a.epc.mnc01.mcc452.3gppnetwork.org",
                "epc.mnc01.mcc452.3gppnetwork.org", 0, avps);
    }

    private static ScreeningConfig.PeeringRules mvnoRules() {
        return new ScreeningConfig.PeeringRules(
                Set.of(S6A),
                Set.of(316, 318),
                Set.of("epc.mnc01.mcc452.3gppnetwork.org"),
                Set.of(IpV4Cidr.parse("10.20.0.0/16")),
                true);
    }

    @Test
    void emptyConfigAllowsEverything() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(ScreeningConfig.of(Map.of()));
        DiaMsg weird = request(999999, 9999, "foreign.example.com", List.of());
        assertTrue(screener.ingressCheck(weird, "unknown-peer").isEmpty());
        assertTrue(screener.checkIp("unknown-peer", "1.2.3.4"));
    }

    @Test
    void allowedPeerPassesAllChecks() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                ScreeningConfig.of(Map.of("mme-01", mvnoRules())));
        DiaMsg ulr = request(S6A, 316, "epc.mnc01.mcc452.3gppnetwork.org", List.of());
        assertTrue(screener.ingressCheck(ulr, "mme-01").isEmpty());
    }

    @Test
    void disallowedAppIdRejectedWith3007() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                ScreeningConfig.of(Map.of("mme-01", mvnoRules())));
        DiaMsg gx = request(16777238, 272, "epc.mnc01.mcc452.3gppnetwork.org", List.of());
        assertEquals(java.util.Optional.of(3007), screener.ingressCheck(gx, "mme-01"));
        assertEquals(1, screener.appRejectCount());
    }

    @Test
    void disallowedCmdCodeRejectedWith3002() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                ScreeningConfig.of(Map.of("mme-01", mvnoRules())));
        DiaMsg pur = request(S6A, 321, "epc.mnc01.mcc452.3gppnetwork.org", List.of());
        assertEquals(java.util.Optional.of(3002), screener.ingressCheck(pur, "mme-01"));
        assertEquals(1, screener.cmdRejectCount());
    }

    @Test
    void spoofedOriginRealmRejected() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                ScreeningConfig.of(Map.of("mme-01", mvnoRules())));
        DiaMsg spoof = request(S6A, 316, "epc.mnc99.mcc999.3gppnetwork.org", List.of());
        assertEquals(java.util.Optional.of(3002), screener.ingressCheck(spoof, "mme-01"));
        assertEquals(1, screener.realmRejectCount());
    }

    @Test
    void realmSuffixBoundaryEnforced() {
        assertTrue(ScreeningServiceImpl.matchesAnyRealmSuffix(
                "epc.mnc01.mcc452.3gppnetwork.org",
                Set.of("mcc452.3gppnetwork.org")));
        assertTrue(ScreeningServiceImpl.matchesAnyRealmSuffix(
                "ims.mnc01.mcc452.3gppnetwork.org",
                Set.of(".mcc452.3gppnetwork.org")));
        assertFalse(ScreeningServiceImpl.matchesAnyRealmSuffix(
                "evilmcc452.3gppnetwork.org",
                Set.of("mcc452.3gppnetwork.org")));
        assertFalse(ScreeningServiceImpl.matchesAnyRealmSuffix(null, Set.of("x.y")));
        assertTrue(ScreeningServiceImpl.matchesAnyRealmSuffix("anything", Set.of("")));
    }

    @Test
    void appCheckRunsBeforeCmdCheck() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                ScreeningConfig.of(Map.of("mme-01", mvnoRules())));
        DiaMsg both = request(16777238, 999, "epc.mnc01.mcc452.3gppnetwork.org", List.of());
        assertEquals(java.util.Optional.of(3007), screener.ingressCheck(both, "mme-01"));
    }

    @Test
    void foreignProxyStateCountedNotRejected() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                ScreeningConfig.of(Map.of("mme-01", mvnoRules())));
        DiaMsg withProxy = request(S6A, 316, "epc.mnc01.mcc452.3gppnetwork.org",
                List.of(DiaAvp.utf8(AvpCodes.PROXY_STATE, "rogue-proxy.example")));
        assertTrue(screener.ingressCheck(withProxy, "mme-01").isEmpty());
        assertEquals(1, screener.foreignProxyStateCount());

        DiaMsg answerNoProxyState = new DiaMsg(1, DiaMsg.FLAG_PROXYABLE, 316, S6A,
                11L, 22L, "sess", "mme-host", "epc.mnc01.mcc452.3gppnetwork.org",
                null, null, 2001,
                List.of(DiaAvp.utf8(AvpCodes.PROXY_STATE, "rogue")));
        assertTrue(screener.ingressCheck(answerNoProxyState, "mme-01").isEmpty());
        assertEquals(1, screener.foreignProxyStateCount(),
                "answers must not bump the proxy-state counter");
    }

    @Test
    void trustedNoProxyOffDoesNotCount() {
        ScreeningConfig.PeeringRules relaxed = new ScreeningConfig.PeeringRules(
                Set.of(), Set.of(), Set.of(), Set.of(), false);
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                ScreeningConfig.of(Map.of("mme-01", relaxed)));
        DiaMsg msg = request(S6A, 316, "anywhere.example",
                List.of(DiaAvp.utf8(AvpCodes.PROXY_STATE, "x")));
        assertTrue(screener.ingressCheck(msg, "mme-01").isEmpty());
        assertEquals(0, screener.foreignProxyStateCount());
    }

    @Test
    void ipAllowlistPerPeering() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                ScreeningConfig.of(Map.of("mme-01", mvnoRules())));
        assertTrue(screener.checkIp("mme-01", "10.20.1.5"));
        assertTrue(screener.checkIp("mme-01", "10.20.255.255"));
        assertFalse(screener.checkIp("mme-01", "10.21.0.1"));
        assertFalse(screener.checkIp("mme-01", "not-an-ip"));
        assertFalse(screener.checkIp("mme-01", "::1"));
        assertTrue(screener.checkIp("other-peer", "192.168.99.99"),
                "peer without IP rules stays allow-all");
    }

    @Test
    void configIsDefensivelyImmutable() {
        ScreeningConfig.PeeringRules rules = new ScreeningConfig.PeeringRules(
                Set.of(1), Set.of(2), Set.of("a"), Set.of(IpV4Cidr.parse("10.0.0.0/8")), false);
        java.util.Map<String, ScreeningConfig.PeeringRules> mutable =
                new java.util.HashMap<>(Map.of("p", rules));
        ScreeningConfig config = ScreeningConfig.of(mutable);
        mutable.put("late", mvnoRules());
        assertFalse(config.peerings().containsKey("late"),
                "later mutation of the source map must not leak into the config");
        assertEquals(rules, config.forPeer("p"));
        assertEquals(ScreeningConfig.PeeringRules.ALLOW_ALL, config.forPeer("missing"));
    }

    @Test
    void failClosedRejectsUnknownPeerWhenRejectUnknownSet() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                new ScreeningConfig(Map.of("mme-01", mvnoRules()), true));
        DiaMsg ulr = request(S6A, 316, "epc.mnc01.mcc452.3gppnetwork.org", List.of());
        assertEquals(java.util.Optional.of(3002), screener.ingressCheck(ulr, "stranger"));
        assertEquals(1, screener.unknownPeerRejectCount());
        assertTrue(screener.ingressCheck(ulr, "mme-01").isEmpty(),
                "provisioned peer still passes");
        assertEquals(java.util.Optional.of(3002),
                screener.ingressCheck(ulr, "another-stranger"),
                "every unlisted peer is rejected while the gate is on");
        assertEquals(2, screener.unknownPeerRejectCount());
    }

    @Test
    void legacyConstructorKeepsAllowUnknownBehaviour() {
        ScreeningServiceImpl screener = new ScreeningServiceImpl(
                ScreeningConfig.of(Map.of()));
        DiaMsg ulr = request(S6A, 316, "epc.mnc01.mcc452.3gppnetwork.org", List.of());
        assertTrue(screener.ingressCheck(ulr, "anyone").isEmpty());
        assertFalse(screener.config().rejectUnknown());
    }
}
