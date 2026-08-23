package et.elisa.dra.lab.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void defaultsMatchLabTopology() {
        Config cfg = Config.parse(new String[0]);
        assertEquals(3869, cfg.listenPort());
        assertEquals(8086, cfg.webPort());
        assertEquals("127.0.0.1", cfg.bind());
        assertFalse(cfg.tcp());
        assertEquals("hss-a.epc.mnc01.mcc452.3gppnetwork.org", cfg.originHost());
        assertEquals("epc.mnc01.mcc452.3gppnetwork.org", cfg.originRealm());
        assertEquals("dra1.epc.mnc01.mcc452.3gppnetwork.org", cfg.peerHost());
        assertEquals(cfg.originRealm(), cfg.peerRealm());
    }

    @Test
    void flagsOverrideDefaults() {
        Config cfg = Config.parse(new String[]{
                "--listen-port", "13969", "--web-port", "18086", "--bind", "0.0.0.0", "--tcp",
                "--origin-host", "hss-x.lab", "--origin-realm", "lab",
                "--peer-host", "dra-x.lab", "--peer-realm", "dra-realm",
                "--subscribers-json", "/tmp/seeds.jsonl", "--status-file", "/tmp/exit.json"});
        assertEquals(13969, cfg.listenPort());
        assertEquals(18086, cfg.webPort());
        assertEquals("0.0.0.0", cfg.bind());
        assertTrue(cfg.tcp());
        assertEquals("hss-x.lab", cfg.originHost());
        assertEquals("lab", cfg.originRealm());
        assertEquals("dra-x.lab", cfg.peerHost());
        assertEquals("dra-realm", cfg.peerRealm());
        assertEquals("/tmp/seeds.jsonl", cfg.subscribersJson());
        assertEquals("/tmp/exit.json", cfg.statusFile());
    }

    @Test
    void originHostFlagOverridesDefault() {
        Config overridden = Config.parse(new String[]{"--origin-host", "other.example"});
        assertEquals("other.example", overridden.originHost());
        Config fallback = Config.parse(new String[0]);
        assertEquals(Config.ORIGIN_HOST, fallback.originHost());
    }

    @Test
    void diameterPortAliasKeptForBackwardCompat() {
        Config legacy = Config.parse(new String[]{"--diameter-port", "13868"});
        assertEquals(13868, legacy.listenPort());
    }

    @Test
    void unknownArgRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Config.parse(new String[]{"--nope"}));
        assertThrows(IllegalArgumentException.class,
                () -> Config.parse(new String[]{"--listen-port"}));
    }
}
