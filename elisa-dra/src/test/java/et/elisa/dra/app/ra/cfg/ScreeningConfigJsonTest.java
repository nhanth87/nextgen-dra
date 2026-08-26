package et.elisa.dra.app.ra.cfg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import et.elisa.dra.core.screen.ScreeningConfig;

class ScreeningConfigJsonTest {

    @Test
    void parsesPeeringRulesAndFailClosedFlag() {
        ScreeningConfig cfg = ScreeningConfigJson.parse("""
                {
                  "defaultAction": "REJECT",
                  "peerings": {
                    "mme-acc": { "apps": [16777251],
                                 "realmSuffixes": ["epc.mnc01.mcc452.3gppnetwork.org"],
                                 "ipPrefixes": ["127.0.0.0/8", "10.0.0.0/8"],
                                 "trustedNoProxy": true }
                  }
                }
                """);
        assertTrue(cfg.rejectUnknown());
        assertTrue(cfg.known("mme-acc"));
        assertFalse(cfg.known("stranger"));
        var rules = cfg.forPeer("mme-acc");
        assertTrue(rules.appIds().contains(16777251));
        assertEquals(2, rules.ipPrefixes().size());
        assertTrue(rules.trustedNoProxy());
    }

    @Test
    void defaultActionAllowMeansNotFailClosed() {
        ScreeningConfig cfg = ScreeningConfigJson.parse("""
                { "defaultAction": "ALLOW", "peerings": {} }
                """);
        assertFalse(cfg.rejectUnknown());
        assertTrue(cfg.peerings().isEmpty());
    }

    @Test
    void missingSectionsFallBackToDefaults() {
        ScreeningConfig cfg = ScreeningConfigJson.parse("{}");
        assertFalse(cfg.rejectUnknown());
        assertTrue(cfg.peerings().isEmpty());
    }

    @Test
    void invalidCidrEntriesAreDropped() {
        ScreeningConfig cfg = ScreeningConfigJson.parse("""
                { "defaultAction": "REJECT",
                  "peerings": { "p": { "ipPrefixes": ["not-a-cidr", "10.0.0.0/8"] } } }
                """);
        assertEquals(1, cfg.forPeer("p").ipPrefixes().size());
    }
}
