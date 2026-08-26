package et.elisa.dra.app.admin;

import et.elisa.dra.core.cfg.RuleSetHolder;
import et.elisa.dra.core.metrics.MetricsNames;
import et.elisa.dra.core.peer.PeerHealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Server-rendered HTML fragments for the htmx hub (gmlc-microjainslee
 * pattern): pages stay thin shells, all dynamic markup comes from here.
 */
@Path("/admin/panel")
@ApplicationScoped
public class AdminPanels {

    private static final String NL = "\n";
    private static final Map<Integer, String> APP_NAMES = Map.ofEntries(
            Map.entry(0, "COMMON"), Map.entry(1, "NASREQ"), Map.entry(3, "Rf/Accounting"),
            Map.entry(4, "Ro/Credit-Control"), Map.entry(16777216, "Cx/Dx"),
            Map.entry(16777236, "Rx"), Map.entry(16777238, "Gx"),
            Map.entry(16777251, "S6a/S6d"), Map.entry(16777252, "S13"),
            Map.entry(16777265, "SWx"), Map.entry(16777302, "Sy"));
    private static final Map<Integer, String> CMD_NAMES = Map.ofEntries(
            Map.entry(257, "CER/CEA"), Map.entry(271, "ACR/ACA"), Map.entry(272, "CCR/CCA"),
            Map.entry(280, "DWR/DWA"), Map.entry(282, "DPR/DPA"), Map.entry(303, "MAR/MAA"),
            Map.entry(305, "SAR/SAA"), Map.entry(306, "LIR/LIA"), Map.entry(308, "UAR/UAA"),
            Map.entry(316, "ULR/ULA"), Map.entry(318, "AIR/AIA"), Map.entry(321, "PUR/PUA"),
            Map.entry(323, "NOR/NOA"), Map.entry(332, "IDR/IDA"), Map.entry(333, "CLR/CLA"));

    private final AdminPort admin;
    private final RuleSetHolder holder;
    private final Instance<et.elisa.dra.app.persist.RouteConfigSink> sinks;
    private final Instance<et.elisa.dra.app.persist.AuditRecorder> audits;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public AdminPanels(AdminPort admin, RuleSetHolder holder,
                       Instance<et.elisa.dra.app.persist.RouteConfigSink> sinks,
                       Instance<et.elisa.dra.app.persist.AuditRecorder> audits) {
        this.admin = admin;
        this.holder = holder;
        this.sinks = sinks;
        this.audits = audits;
    }

    public AdminPanels() {
        this(AdminPort.NOOP, new RuleSetHolder(), null, null);
    }

    /** Test/memory-only wiring without CDI instances. */
    public AdminPanels(AdminPort admin, RuleSetHolder holder) {
        this(admin, holder, null, null);
    }

    // ── KPI strip ────────────────────────────────────────────────────────

    @GET
    @Path("kpis")
    @Produces(MediaType.TEXT_HTML)
    public String kpis() {
        Map<String, Long> c = new TreeMap<>(admin.telemetry().snapshot());
        long tx = c.getOrDefault(MetricsNames.TX_TOTAL, 0L);
        long txa = c.getOrDefault(MetricsNames.TX_ACTIVE, 0L);
        long bind = admin.bindingsCount();
        int ready = 0;
        int total = 0;
        for (PeerHealth h : admin.peersHealth().values()) {
            total++;
            if (h.ready()) {
                ready++;
            }
        }
        return kpi("Diameter tx total", tx)
                + kpi("Tx active (in flight)", txa)
                + kpi("Bindings stored", bind)
                + kpi("Peers READY", ready + " / " + total);
    }

    private static String kpi(String label, Object value) {
        return "<div class=\"card kpi\"><small>" + label + "</small><b>" + value + "</b></div>";
    }

    // ── Peers ────────────────────────────────────────────────────────────

    @GET
    @Path("peers")
    @Produces(MediaType.TEXT_HTML)
    public String peers() {
        StringBuilder sb = new StringBuilder();
        Set<String> disabled = admin.disabledPeers();
        Map<String, PeerHealth> health = admin.peersHealth();
        sb.append("<table><thead><tr><th>Peer</th><th>State</th><th>Ready</th>")
          .append("<th>Admin</th><th>Outstanding</th><th>Apps</th><th>Ops</th></tr></thead><tbody>");
        if (health.isEmpty()) {
            sb.append("<tr><td colspan=\"7\" class=\"muted\">")
              .append(admin.live() ? "no peers registered" : "relay plane not attached")
              .append("</td></tr>");
        }
        health.forEach((id, p) -> {
            boolean off = disabled.contains(id);
            sb.append("<tr><td>").append(esc(id)).append("</td><td>").append(esc(p.state()))
              .append("</td><td>").append(pill(p.ready(), "READY", "NOT READY"))
              .append("</td><td>")
              .append(off ? pill(false, "", "DRAINING") : pill(true, "ACTIVE", ""))
              .append("</td><td>").append(p.outstanding()).append("</td><td class=\"muted\">")
              .append(p.advertisedApps().size())
              .append("</td><td>")
              .append("<button hx-post=\"/admin/panel/peers/").append(esc(id))
              .append("/disable\" hx-target=\"#peers-panel\" hx-swap=\"innerHTML\">disable</button> ")
              .append("<button hx-post=\"/admin/panel/peers/").append(esc(id))
              .append("/enable\" hx-target=\"#peers-panel\" hx-swap=\"innerHTML\">enable</button>")
              .append("</td></tr>");
        });
        sb.append("</tbody></table>");
        return sb.toString();
    }

    @POST
    @Path("peers/{id}/{op}")
    @Produces(MediaType.TEXT_HTML)
    public String peerOp(@PathParam("id") String id, @PathParam("op") String op) {
        if ("disable".equals(op)) {
            admin.disablePeer(id);
        } else if ("enable".equals(op)) {
            admin.enablePeer(id);
        }
        return peers();
    }

    // ── Telemetry ────────────────────────────────────────────────────────

    @GET
    @Path("telemetry")
    @Produces(MediaType.TEXT_HTML)
    public String telemetry() {
        var port = admin.telemetry();
        Map<String, Long> c = new TreeMap<>(port.snapshot());
        StringBuilder sb = new StringBuilder();
        sb.append(kvLine("live", port.live()));
        if (c.isEmpty()) {
            sb.append("<div class=\"muted\">no counters yet</div>");
        }
        c.forEach((k, v) -> sb.append(kvLine(k, v)));
        return sb.toString();
    }

    // ── Bindings ─────────────────────────────────────────────────────────

    @GET
    @Path("bindings")
    @Produces(MediaType.TEXT_HTML)
    public String bindings(@QueryParam("limit") @DefaultValue("30") int limit) {
        List<Map<String, Object>> entries = admin.bindingsSample(Math.min(Math.max(limit, 0), 100));
        StringBuilder sb = new StringBuilder();
        sb.append(kvLine("count", admin.bindingsCount())).append("<div class=\"sec\">");
        if (entries.isEmpty()) {
            sb.append("<span class=\"muted\">none captured yet</span>");
        } else {
            Instant now = Instant.now();
            sb.append("<table><thead><tr><th>Key</th><th>→ Peer</th><th>Group</th>")
              .append("<th>Expires</th></tr></thead><tbody>");
            for (Map<String, Object> e : entries) {
                long secs = e.get("expiresAt") == null ? 0
                        : Duration.between(now, Instant.parse(e.get("expiresAt").toString()))
                                .toSeconds();
                String exp = secs <= 0 ? "expired" : human(secs);
                sb.append("<tr><td class=\"mono\" title=\"").append(esc(str(e.get("key"))))
                  .append("\">").append(esc(str(e.get("key"))))
                  .append("</td><td>").append(esc(str(e.get("peerId"))))
                  .append("</td><td class=\"muted\">").append(esc(str(e.get("groupId"))))
                  .append("</td><td class=\"muted mono\">").append(exp).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String human(long secs) {
        if (secs >= 3600) {
            return (secs / 3600) + "h" + ((secs % 3600) / 60) + "m";
        }
        if (secs >= 60) {
            return (secs / 60) + "m" + (secs % 60) + "s";
        }
        return secs + "s";
    }

    // ── Effective config ─────────────────────────────────────────────────

    @GET
    @Path("config")
    @Produces(MediaType.TEXT_HTML)
    public String config() {
        Map<String, Object> c = admin.runtimeConfig();
        StringBuilder sb = new StringBuilder();
        if (!Boolean.TRUE.equals(c.get("live")) || c.get("originHost") == null) {
            return "<span class=\"muted\">relay plane not initialized</span>";
        }
        sb.append(kvLine("originHost", esc(str(c.get("originHost")))));
        Object realms = c.get("realms");
        if (realms instanceof List<?> l) {
            sb.append(kvLine("realms", esc(join(l))));
        }
        sb.append(kvLine("watchdog / Tw", c.get("watchdogMillis") + " ms / "
                + c.get("twTimeoutMillis") + " ms"));
        sb.append(kvLine("failover maxRetries · sweep", c.get("failoverMaxRetries")
                + " · " + c.get("txSweepPeriodMillis") + " ms"));
        Object rulesVersion = c.get("rulesVersion");
        if (rulesVersion != null) {
            sb.append(kvLine("rules version", rulesVersion));
        }
        sb.append("<div class=\"sec\"><b class=\"mono\">peers</b>");
        if (c.get("peers") instanceof List<?> peers && !peers.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ps = (List<Map<String, Object>>) peers;
            for (Map<String, Object> p : ps) {
                sb.append(kvLine(esc(str(p.get("id"))),
                        esc(str(p.get("role"))) + " · " + esc(str(p.get("transport")))
                        + " · " + esc(str(p.get("host"))) + ":" + str(p.get("port"))
                        + " · g=" + esc(str(p.get("group"))) + " · w=" + str(p.get("weight"))));
            }
        } else {
            sb.append("<div class=\"muted\">none</div>");
        }
        sb.append("</div>");
        if (c.get("overload") instanceof Map<?, ?> ov) {
            sb.append("<div class=\"sec\"><b class=\"mono\">overload gate</b>")
              .append(kvLine("global / peer rate", str(ov.get("globalRatePerSec")) + "/s · "
                      + str(ov.get("peerRatePerSec")) + "/s"))
              .append("</div>");
        }
        if (c.get("topologyHiding") instanceof Map<?, ?> th) {
            sb.append("<div class=\"sec\"><b class=\"mono\">topology hiding</b>")
              .append(kvLine("pseudo hosts", esc(str(th.get("pseudoPrefix"))) + "×"
                      + str(th.get("pseudoCount")) + " · suffix "
                      + esc(str(th.get("internalSuffix")))))
              .append("</div>");
        }
        if (c.get("screening") instanceof Map<?, ?> sc) {
            sb.append("<div class=\"sec\"><b class=\"mono\">screening</b>")
              .append(kvLine("mode", esc(str(sc.get("mode")))))
              .append("</div>");
        }
        if (c.get("bindings") instanceof Map<?, ?> bd) {
            sb.append("<div class=\"sec\"><b class=\"mono\">binding store</b>")
              .append(kvLine("store · TTL", esc(str(bd.get("store"))) + " · "
                      + str(bd.get("ttlDefaultSeconds")) + "s"))
              .append("</div>");
        }
        return sb.toString();
    }

    // ── Routing rules ────────────────────────────────────────────────────

    @GET
    @Path("rules")
    @Produces(MediaType.TEXT_HTML)
    public String rules() {
        StringBuilder sb = new StringBuilder();
        JsonNode root;
        try {
            root = mapper.readTree(holder.currentJson());
        } catch (Exception e) {
            return "<span class=\"muted\">rule set unavailable</span>";
        }
        JsonNode self = root.path("self");
        sb.append(kvLine("version", root.path("version").asInt(0)))
          .append(kvLine("originHost", esc(self.path("originHost").asText("-"))))
          .append(kvLine("realms", esc(joinIter(self.path("realms")))));
        sb.append(groupsHtml(root.path("peerGroups")));
        sb.append(rulesTable(root.path("rules")));
        return sb.toString();
    }

    @GET
    @Path("rules/raw")
    @Produces(MediaType.TEXT_HTML)
    public String rulesRaw() {
        String json = holder.currentJson();
        return "<textarea id=\"rules-json\" name=\"body\" spellcheck=\"false\">"
                + esc(json) + "</textarea>";
    }

    @POST
    @Path("rules/apply")
    @Produces(MediaType.TEXT_HTML)
    public String rulesApply(@FormParam("body") String body) {
        List<String> errors = holder.applyCandidate(body == null ? "" : body);
        if (!errors.isEmpty()) {
            StringBuilder out = new StringBuilder("<div class=\"pill bad\">✗ rejected — last-good v")
                    .append(holder.version()).append(" kept</div><ul class=\"muted\">");
            errors.forEach(e -> out.append("<li class=\"mono\">").append(esc(e)).append("</li>"));
            out.append("</ul>");
            return out.toString();
        }
        boolean persisted = false;
        try {
            if (sinks != null && sinks.isResolvable()) {
                var sink = sinks.get();
                sink.persistApplied(holder.version(), body == null ? "" : body);
                persisted = true;
            }
            if (audits != null && audits.isResolvable()) {
                audits.get().record("admin", "rules.apply",
                        "{\"version\":" + holder.version() + "}");
            }
        } catch (RuntimeException ignored) {
            persisted = false;
        }
        return "<div class=\"pill ok\">✓ applied v" + holder.version()
                + (persisted ? " · persisted to route_config SoT" : " · memory-only")
                + "</div>";
    }

    private String groupsHtml(JsonNode groups) {
        StringBuilder sb = new StringBuilder("<h2>Peer groups &amp; LB</h2><div class=\"grid3\">");
        boolean any = false;
        var it = groups.fields();
        while (it.hasNext()) {
            var e = it.next();
            any = true;
            JsonNode g = e.getValue();
            sb.append("<div><b class=\"mono\">").append(esc(e.getKey())).append("</b><div class=\"sec\">")
              .append(kvLine("LB", esc(g.path("lb").asText("?"))));
            JsonNode fo = g.path("failover");
            if (fo.isObject()) {
                sb.append(kvLine("failover", fo.path("enabled").asBoolean()
                        ? "on · maxRetries=" + fo.path("maxRetries").asInt() : "off"));
            }
            sb.append("<div class=\"sec\">");
            for (JsonNode p : g.path("peers")) {
                sb.append("<span class=\"chip\">").append(esc(p.path("id").asText()))
                  .append(" · w=").append(p.path("weight").asInt()).append("</span> ");
            }
            sb.append("</div></div></div>");
        }
        if (!any) {
            sb.append("<span class=\"muted\">no peer groups</span>");
        }
        return sb.append("</div>").toString();
    }

    private String rulesTable(JsonNode rules) {
        List<JsonNode> sorted = new java.util.ArrayList<>();
        rules.forEach(sorted::add);
        sorted.sort(java.util.Comparator.comparingInt(r -> r.path("priority").asInt(Integer.MAX_VALUE)));
        StringBuilder sb = new StringBuilder("<h2>Rules — evaluated in priority order, first match wins</h2>")
                .append("<table><thead><tr><th style=\"width:70px\">Priority</th>")
                .append("<th style=\"width:180px\">Rule</th><th>When</th><th>Then</th></tr></thead><tbody>");
        if (sorted.isEmpty()) {
            sb.append("<tr><td colspan=\"4\" class=\"muted\">engine empty</td></tr>");
        }
        for (JsonNode r : sorted) {
            sb.append("<tr><td class=\"mono\">").append(r.path("priority").asInt())
              .append("</td><td><b>").append(esc(r.path("name").asText()))
              .append("</b></td><td><div class=\"rule-flow\">")
              .append(matcherHtml(r.path("when")))
              .append("</div></td><td><div class=\"rule-flow\">")
              .append(actionHtml(r.path("then")))
              .append("</div></td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    private String matcherHtml(JsonNode m) {
        if (m == null || !m.isObject() || m.isEmpty()) {
            return "";
        }
        String first = m.fieldNames().next();
        JsonNode v = m.get(first);
        switch (first) {
            case "and": return joinChips(v, "AND");
            case "or": return joinChips(v, "OR");
            case "not": return chip("", "NOT") + matcherHtml(v);
            case "app": return chip("match", appName(v.asInt()));
            case "cmd":
                StringBuilder cs = new StringBuilder();
                for (JsonNode n : v) {
                    if (cs.length() > 0) {
                        cs.append(", ");
                    }
                    cs.append(cmdName(n.asInt()));
                }
                return chip("match", cs.toString());
            case "realm":
            case "host":
                return chip("", esc(first.toUpperCase()) + " "
                        + esc(v.path("field").asText()) + " "
                        + esc(v.path("op").asText()) + " " + esc(v.path("value").asText("")));
            case "avp":
                String val = v.path("value").asText("");
                return chip("avp", esc(v.path("path").asText()) + " "
                        + esc(v.path("op").asText()) + (val.isBlank() ? "" : " " + esc(val)));
            case "plmnFrom": {
                StringBuilder bits = new StringBuilder(esc(v.path("plmnFrom").asText()));
                if (v.hasNonNull("in")) {
                    bits.append(" in {").append(joinIter(v.get("in"))).append("}");
                }
                if (v.hasNonNull("notIn")) {
                    bits.append(" not-in {").append(joinIter(v.get("notIn"))).append("}");
                }
                return chip("plmn", bits.toString());
            }
            case "drmpAtLeast": return chip("", "DRMP ≥ " + v.asInt());
            case "ingressPeerIn": return chip("", "ingress ∈ {" + joinIter(v) + "}");
            case "flag": return chip("", "flag " + esc(v.asText()));
            case "always": return chip(v.asBoolean() ? "match" : "", "ALWAYS");
            default: return chip("", esc(first));
        }
    }

    private String actionHtml(JsonNode t) {
        if (t == null || !t.isObject()) {
            return "<span class=\"muted\">–</span>";
        }
        if (t.hasNonNull("forward")) {
            JsonNode f = t.get("forward");
            StringBuilder out = new StringBuilder(chip("act", "FORWARD → " + f.path("group").asText()));
            JsonNode st = f.get("sticky");
            if (st != null && st.isObject()) {
                out.append(' ').append(chip("", "sticky " + st.path("key").asText()
                        + " TTL " + st.path("ttlSecs").asLong()));
            }
            if (f.hasNonNull("th") && !"OFF".equalsIgnoreCase(f.get("th").asText())) {
                out.append(' ').append(chip("", "TH " + f.get("th").asText()));
            }
            if (f.path("allowHairpin").asBoolean(false)) {
                out.append(' ').append(chip("", "hairpin ok"));
            }
            return out.toString();
        }
        if (t.hasNonNull("reject")) {
            JsonNode rj = t.get("reject");
            String reason = rj.path("reason").asText("");
            return chip("act", "REJECT " + rj.path("resultCode").asInt())
                    + (reason.isBlank() ? "" : " <span class=\"muted mono\">" + esc(reason) + "</span>");
        }
        if (t.hasNonNull("redirect")) {
            JsonNode rd = t.get("redirect");
            return chip("act", "REDIRECT "
                    + (rd.hasNonNull("host") ? rd.get("host").asText() : rd.path("realm").asText()));
        }
        return "<span class=\"muted\">?</span>";
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static String appName(int id) {
        String n = APP_NAMES.get(id);
        return n != null ? n + " (" + id + ")" : "app " + id;
    }

    private static String cmdName(int code) {
        String n = CMD_NAMES.get(code);
        return n != null ? n : "cmd " + code;
    }

    private String joinChips(JsonNode arr, String sepWord) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) {
                out.append("<span class=\"arrow\">").append(sepWord).append("</span>");
            }
            out.append(matcherHtml(arr.get(i)));
        }
        return out.toString();
    }

    private static String join(Iterable<?> l) {
        StringBuilder out = new StringBuilder();
        for (Object o : l) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(o);
        }
        return out.toString();
    }

    private static String joinIter(JsonNode arr) {
        StringBuilder out = new StringBuilder();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(n.asText());
            }
        }
        return out.toString();
    }

    private static String kvLine(String k, Object v) {
        return "<div class=\"kv\"><span class=\"muted\">" + k + "</span><b>"
                + (v == null ? "–" : v) + "</b></div>";
    }

    private static String pill(boolean ok, String okText, String badText) {
        return "<span class=\"pill " + (ok ? "ok" : "bad") + "\">"
                + (ok ? okText : badText) + "</span>";
    }

    private static String chip(String kind, String label) {
        return "<span class=\"chip " + kind + "\">" + label + "</span>";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
