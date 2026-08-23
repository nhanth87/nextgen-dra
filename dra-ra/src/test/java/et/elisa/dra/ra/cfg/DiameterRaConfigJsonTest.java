package et.elisa.dra.ra.cfg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import et.elisa.dra.ra.DiameterRaConfig;
import et.elisa.dra.ra.PeerConfig;

class DiameterRaConfigJsonTest {

    private static final String CONTRACT_JSON = """
            {
              "peers": [{
                "id": "hss-a",
                "host": "10.0.0.11",
                "port": 3868,
                "role": "SERVER",
                "transport": "TCP",
                "advertisedApps": [16777251],
                "group": "mvno-hss-pool",
                "weight": 70,
                "maxOutstanding": 2000
              }],
              "originHost": "dra1.elisa.lab",
              "realms": ["epc.mnc01.mcc452.3gppnetwork.org"],
              "watchdogIntervalMillis": 30000,
              "twTimeoutMillis": 5000
            }
            """;

    @Test
    void parsesExactContractShape() throws Exception {
        DiameterRaConfig cfg = DiameterRaConfigJson.parse(CONTRACT_JSON);
        assertEquals("dra1.elisa.lab", cfg.originHost());
        assertEquals(Set.of("epc.mnc01.mcc452.3gppnetwork.org"), cfg.realms());
        assertEquals(30000, cfg.watchdogIntervalMillis());
        assertEquals(5000, cfg.twTimeoutMillis());
        assertEquals(1, cfg.peers().size());
        PeerConfig p = cfg.peers().get(0);
        assertEquals("hss-a", p.id());
        assertEquals("10.0.0.11", p.host());
        assertEquals(3868, p.port());
        assertEquals("SERVER", p.role());
        assertEquals("TCP", p.transport());
        assertEquals(Set.of(16777251), p.advertisedApps());
        assertEquals("mvno-hss-pool", p.group());
        assertEquals(70, p.weight());
        assertEquals(2000, p.maxOutstanding());
    }

    @Test
    void writeThenParseRoundTrips() throws Exception {
        var original = new DiameterRaConfig(
                List.of(new PeerConfig("pcrf-1", "10.1.0.5", 3868, "CLIENT", "SCTP",
                        Set.of(16777238, 16777236), "pcrf-pool", 50, 1500)),
                "dra2.elisa.lab",
                Set.of("epc.mnc01.mcc452.3gppnetwork.org", "ims.mnc01.mcc452.3gppnetwork.org"),
                25000, 4000);
        String json = DiameterRaConfigJson.write(original);
        var parsed = DiameterRaConfigJson.parse(json);
        assertEquals(original.originHost(), parsed.originHost());
        assertEquals(original.realms(), parsed.realms());
        assertEquals(original.watchdogIntervalMillis(), parsed.watchdogIntervalMillis());
        assertEquals(original.twTimeoutMillis(), parsed.twTimeoutMillis());
        PeerConfig a = original.peers().get(0);
        PeerConfig b = parsed.peers().get(0);
        assertEquals(a, b);
    }

    @Test
    void defaultsFillOptionalPeerFields() throws Exception {
        String minimal = """
                {
                  "peers": [{"id": "hss-x", "host": "10.0.0.99"}],
                  "originHost": "dra1.elisa.lab"
                }
                """;
        var cfg = DiameterRaConfigJson.parse(minimal);
        PeerConfig p = cfg.peers().get(0);
        assertEquals(3868, p.port());
        assertEquals("SERVER", p.role());
        assertEquals("TCP", p.transport());
        assertTrue(p.advertisedApps().isEmpty());
        assertEquals("default", p.group());
        assertEquals(1, p.weight());
        assertEquals(PeerConfig.DEFAULT_MAX_OUTSTANDING, p.maxOutstanding());
        assertEquals(DiameterRaConfig.DEFAULT_WATCHDOG_MILLIS, cfg.watchdogIntervalMillis());
        assertEquals(DiameterRaConfig.DEFAULT_TW_MILLIS, cfg.twTimeoutMillis());
    }

    @Test
    void missingOriginHostRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DiameterRaConfigJson.parse("{\"peers\":[]}"));
    }

    @Test
    void invalidRoleRejectedThroughJson() {
        String bad = """
                {"peers":[{"id":"x","host":"h","role":"PEER"}],"originHost":"o"}
                """;
        assertThrows(IllegalArgumentException.class, () -> DiameterRaConfigJson.parse(bad));
    }

    @Test
    void singlePeerFactoryMapsLegacyFields() {
        var cfg = DiameterRaConfig.singlePeer(
                "0.0.0.0", 3868, "mobicents.org", "server.mobicents.org",
                "epc-product", 0L, true, false,
                "127.0.0.1", 3868, "peer.epc.lab", "epc.lab",
                "server", 30_000L);
        assertEquals(1, cfg.peers().size());
        PeerConfig link = cfg.peers().get(0);
        assertEquals(DiameterRaConfig.LEGACY_LINK_ID, link.id());
        assertEquals("127.0.0.1", link.host());
        assertEquals(3868, link.port());
        assertEquals("SERVER", link.role());
        assertEquals("TCP", link.transport());
        assertEquals("server.mobicents.org", cfg.originHost());
        assertEquals(30_000L, cfg.watchdogIntervalMillis());
        assertTrue(cfg.realms().contains("mobicents.org"));
        assertTrue(cfg.realms().contains("epc.lab"));
        assertEquals(DiameterRaConfig.DEFAULT_TW_MILLIS, cfg.twTimeoutMillis());
    }
}
