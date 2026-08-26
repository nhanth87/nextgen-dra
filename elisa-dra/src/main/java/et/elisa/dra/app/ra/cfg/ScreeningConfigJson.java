package et.elisa.dra.app.ra.cfg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import et.elisa.dra.core.screen.IpV4Cidr;
import et.elisa.dra.core.screen.ScreeningConfig;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Parses configs/dra-screening.json into a {@link ScreeningConfig}. */
public final class ScreeningConfigJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScreeningConfigJson() {
    }

    public static ScreeningConfig parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            boolean rejectUnknown = root.path("defaultAction").asText("ALLOW")
                    .equalsIgnoreCase("REJECT");
            Map<String, ScreeningConfig.PeeringRules> peerings = new LinkedHashMap<>();
            JsonNode peersNode = root.path("peerings");
            if (peersNode.isObject()) {
                peersNode.fields().forEachRemaining(e ->
                        peerings.put(e.getKey(), parsePeering(e.getValue())));
            }
            return new ScreeningConfig(peerings, rejectUnknown);
        } catch (UncheckedIOException | java.io.IOException e) {
            throw new IllegalArgumentException("invalid dra-screening.json: " + e.getMessage(), e);
        }
    }

    private static ScreeningConfig.PeeringRules parsePeering(JsonNode n) {
        return new ScreeningConfig.PeeringRules(
                intSet(n.path("apps")),
                intSet(n.path("cmds")),
                stringSet(n.path("realmSuffixes")),
                cidrSet(n.path("ipPrefixes")),
                n.path("trustedNoProxy").asBoolean(false));
    }

    private static Set<Integer> intSet(JsonNode n) {
        if (!n.isArray()) {
            return Set.of();
        }
        return StreamSupport.stream(n.spliterator(), false)
                .map(JsonNode::asInt)
                .collect(Collectors.toSet());
    }

    private static Set<String> stringSet(JsonNode n) {
        if (!n.isArray()) {
            return Set.of();
        }
        return StreamSupport.stream(n.spliterator(), false)
                .map(JsonNode::asText)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private static Set<IpV4Cidr> cidrSet(JsonNode n) {
        if (!n.isArray()) {
            return Set.of();
        }
        return StreamSupport.stream(n.spliterator(), false)
                .map(JsonNode::asText)
                .map(s -> {
                    try {
                        return IpV4Cidr.parse(s);
                    } catch (RuntimeException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Resolves configs/dra-screening.json the same way dra-peers.json is found. */
    public static ScreeningConfig load() {
        for (Path candidate : List.of(Path.of("configs/dra-screening.json"),
                Path.of("../configs/dra-screening.json"))) {
            if (Files.exists(candidate)) {
                try {
                    return parse(Files.readString(candidate));
                } catch (Exception e) {
                    throw new IllegalStateException("invalid " + candidate, e);
                }
            }
        }
        return null;
    }
}
