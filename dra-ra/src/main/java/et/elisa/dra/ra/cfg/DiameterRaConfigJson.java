package et.elisa.dra.ra.cfg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import et.elisa.dra.ra.DiameterRaConfig;
import et.elisa.dra.ra.PeerConfig;

public final class DiameterRaConfigJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DiameterRaConfigJson() {
    }

    public static DiameterRaConfig parse(String json) throws IOException {
        return parse(MAPPER.readTree(json));
    }

    public static DiameterRaConfig parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("dra config must be a JSON object");
        }
        List<PeerConfig> peers = new ArrayList<>();
        JsonNode peersNode = root.get("peers");
        if (peersNode != null && !peersNode.isNull()) {
            if (!peersNode.isArray()) {
                throw new IllegalArgumentException("peers must be an array");
            }
            for (JsonNode p : peersNode) {
                peers.add(parsePeer(p));
            }
        }
        String originHost = text(root.get("originHost"), null);
        if (originHost == null) {
            throw new IllegalArgumentException("originHost required");
        }
        Set<String> realms = new LinkedHashSet<>();
        JsonNode realmsNode = root.get("realms");
        if (realmsNode != null && realmsNode.isArray()) {
            for (JsonNode r : realmsNode) {
                if (!r.isNull()) {
                    realms.add(r.asText());
                }
            }
        }
        long watchdog = root.path("watchdogIntervalMillis").asLong(DiameterRaConfig.DEFAULT_WATCHDOG_MILLIS);
        long tw = root.path("twTimeoutMillis").asLong(DiameterRaConfig.DEFAULT_TW_MILLIS);
        return new DiameterRaConfig(peers, originHost, realms, watchdog, tw);
    }

    public static ObjectNode toJson(DiameterRaConfig config) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode peers = root.putArray("peers");
        config.peers().forEach(p -> {
            ObjectNode n = peers.addObject();
            n.put("id", p.id());
            n.put("host", p.host());
            n.put("port", p.port());
            if (p.listenPort() != null) {
                n.put("listenPort", p.listenPort());
            }
            if (p.remoteIdentityHost() != null) {
                n.put("remoteHost", p.remoteIdentityHost());
            }
            if (p.remoteIdentityRealm() != null) {
                n.put("remoteRealm", p.remoteIdentityRealm());
            }
            n.put("role", p.role());
            n.put("transport", p.transport());
            ArrayNode apps = n.putArray("advertisedApps");
            p.advertisedApps().stream().sorted().forEach(apps::add);
            n.put("group", p.group());
            n.put("weight", p.weight());
            n.put("maxOutstanding", p.maxOutstanding());
        });
        root.put("originHost", config.originHost());
        ArrayNode realms = root.putArray("realms");
        config.realms().forEach(realms::add);
        root.put("watchdogIntervalMillis", config.watchdogIntervalMillis());
        root.put("twTimeoutMillis", config.twTimeoutMillis());
        return root;
    }

    public static String write(DiameterRaConfig config) throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJson(config));
    }

    private static PeerConfig parsePeer(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("peer entry must be a JSON object");
        }
        String id = requiredText(node, "id");
        String host = requiredText(node, "host");
        int port = node.path("port").asInt(3868);
        Integer listenPort = node.hasNonNull("listenPort")
                && node.get("listenPort").isInt() ? node.get("listenPort").asInt() : null;
        String remoteHost = text(node.get("remoteHost"), null);
        String remoteRealm = text(node.get("remoteRealm"), null);
        Set<Integer> apps = new HashSet<>();
        JsonNode appsNode = node.get("advertisedApps");
        if (appsNode != null && appsNode.isArray()) {
            for (JsonNode a : appsNode) {
                apps.add(a.asInt());
            }
        }
        return new PeerConfig(
                id,
                host,
                port,
                listenPort,
                remoteHost,
                remoteRealm,
                text(node.get("role"), PeerConfig.ROLE_SERVER),
                text(node.get("transport"), PeerConfig.TRANSPORT_TCP),
                apps,
                text(node.get("group"), PeerConfig.DEFAULT_GROUP),
                node.path("weight").asInt(PeerConfig.DEFAULT_WEIGHT),
                node.path("maxOutstanding").asInt(PeerConfig.DEFAULT_MAX_OUTSTANDING));
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        String s = v == null || v.isNull() ? null : v.asText();
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("peer field '" + field + "' required");
        }
        return s;
    }

    private static String text(JsonNode node, String fallback) {
        return node == null || node.isNull() ? fallback : node.asText();
    }
}
