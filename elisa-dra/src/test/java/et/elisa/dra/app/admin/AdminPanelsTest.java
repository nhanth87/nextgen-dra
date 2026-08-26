package et.elisa.dra.app.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import et.elisa.dra.core.cfg.RuleSetHolder;
import et.elisa.dra.core.peer.PeerHealth;

class AdminPanelsTest {

    private static final String VALID_V1 = """
            {"version":1,
             "self":{"originHost":"dra1.elisa.lab","realms":["epc.lab"]},
             "peerGroups":{"mvno-hss-pool":{"lb":"WEIGHTED_RR",
               "peers":[{"id":"hss-a","weight":70},{"id":"hss-b","weight":30}],
               "failover":{"enabled":true,"maxRetries":1}}},
             "rules":[
               {"name":"s6a-mvno","priority":100,
                "when":{"and":[{"app":16777251},
                  {"avp":{"path":"IMSI","op":"PREFIX","value":"4520402"}}]},
                "then":{"forward":{"group":"mvno-hss-pool",
                  "sticky":{"key":"IMSI","ttlSecs":86400}}}},
               {"name":"default-drop","priority":65000,"when":{"always":true},
                "then":{"reject":{"resultCode":3002,"reason":"no-route"}}}]}
            """;

    private static AdminPort stubPort() {
        return new AdminPort() {
            @Override
            public boolean live() {
                return true;
            }

            @Override
            public Map<String, PeerHealth> peersHealth() {
                return Map.of(
                        "hss-a", new PeerHealth("hss-a", true, true, true, 3, Set.of(), "OPEN"),
                        "mme-acc", new PeerHealth("mme-acc", false, false, true, 0, Set.of(), "IDLE"));
            }

            @Override
            public long bindingsCount() {
                return 7;
            }

            @Override
            public TelemetryPort telemetry() {
                return new TelemetryPort() {
                    @Override
                    public boolean live() {
                        return true;
                    }

                    @Override
                    public Map<String, Long> snapshot() {
                        return Map.of(et.elisa.dra.core.metrics.MetricsNames.TX_TOTAL, 42L,
                                et.elisa.dra.core.metrics.MetricsNames.TX_ACTIVE, 1L);
                    }
                };
            }

            @Override
            public List<Map<String, Object>> bindingsSample(int limit) {
                return List.of(Map.of("key", "IMSI:4520402000000001", "peerId", "hss-a",
                        "groupId", "mvno-hss-pool",
                        "expiresAt", "2099-01-01T00:00:00Z"));
            }

            @Override
            public Map<String, Object> runtimeConfig() {
                return Map.of("live", true, "originHost", "dra1.elisa.lab",
                        "realms", List.of("epc.lab"),
                        "peers", List.of(Map.of("id", "hss-a", "role", "CLIENT",
                                "transport", "SCTP", "host", "127.0.0.1", "port", 3869,
                                "group", "mvno-hss-pool", "weight", 70)),
                        "overload", Map.of("globalRatePerSec", 50000, "peerRatePerSec", 5000),
                        "topologyHiding", Map.of("pseudoPrefix", "dra-edge", "pseudoCount", 4),
                        "screening", Map.of("mode", "FAIL_CLOSED"),
                        "bindings", Map.of("store", "durable(write-behind)",
                                "ttlDefaultSeconds", 86400));
            }

            @Override
            public Set<String> disabledPeers() {
                return Set.of("mme-acc");
            }

            @Override
            public boolean enablePeer(String peerId) {
                return true;
            }

            @Override
            public boolean disablePeer(String peerId) {
                return true;
            }
        };
    }

    @Test
    void kpisRenderCounts() {
        String html = new AdminPanels(stubPort(), new RuleSetHolder()).kpis();
        assertTrue(html.contains(">42</b>"));
        assertTrue(html.contains("1 / 2"));
    }

    @Test
    void peersPanelMarksDrainedPeerAndRendersOpsButtons() {
        String html = new AdminPanels(stubPort(), new RuleSetHolder()).peers();
        assertTrue(html.contains("DRAINING"));
        assertTrue(html.contains("READY"));
        assertTrue(html.contains("/admin/panel/peers/hss-a/disable"));
    }

    @Test
    void bindingsPanelListsEntriesWithHumanExpiry() {
        String html = new AdminPanels(stubPort(), new RuleSetHolder()).bindings(10);
        assertTrue(html.contains("IMSI:4520402000000001"));
        assertTrue(html.contains("hss-a"));
        assertTrue(html.contains("count") && html.contains(">7<"));
    }

    @Test
    void configPanelShowsEffectiveValuesAndSections() {
        String html = new AdminPanels(stubPort(), new RuleSetHolder()).config();
        assertTrue(html.contains("dra1.elisa.lab"));
        assertTrue(html.contains("FAIL_CLOSED"));
        assertTrue(html.contains("durable(write-behind)"));
        assertTrue(html.contains("50000"));
    }

    @Test
    void telemetryPanelListsCountersSorted() {
        String html = new AdminPanels(stubPort(), new RuleSetHolder()).telemetry();
        int tx = html.indexOf("dra_tx_active");
        int total = html.indexOf("dra_tx_total");
        assertTrue(tx >= 0 && total > tx, "sorted alphabetically");
    }

    @Test
    void rulesPanelRendersStructuredMatchersActionsGroups() {
        RuleSetHolder holder = new RuleSetHolder();
        holder.applyCandidate(VALID_V1);
        String html = new AdminPanels(stubPort(), holder).rules();
        assertTrue(html.contains("S6a/S6d (16777251)"), "app-id human name");
        assertTrue(html.contains("IMSI PREFIX 4520402"), "avp matcher chip");
        assertTrue(html.contains("FORWARD → mvno-hss-pool"));
        assertTrue(html.contains("sticky IMSI TTL 86400"));
        assertTrue(html.contains("REJECT 3002") || html.contains("REJECT 3002 ".trim()));
        assertTrue(html.contains("WEIGHTED_RR"));
        assertTrue(html.contains("maxRetries=1"));
        assertTrue(html.indexOf("100") < html.indexOf("65000"), "priority order");
    }

    @Test
    void rulesRawEscapesJsonInsideTextarea() {
        RuleSetHolder holder = new RuleSetHolder();
        holder.applyCandidate(VALID_V1);
        String html = new AdminPanels(stubPort(), holder).rulesRaw();
        assertTrue(html.startsWith("<textarea"));
        assertFalseContains(html, "{\"version\":1}</textarea>");
    }

    private static void assertFalseContains(String haystack, String needle) {
        org.junit.jupiter.api.Assertions.assertFalse(haystack.contains(needle));
    }
}
