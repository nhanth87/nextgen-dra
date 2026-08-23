package et.elisa.dra.core.cfg;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import et.elisa.dra.core.engine.Action;
import et.elisa.dra.core.engine.AvpOp;
import et.elisa.dra.core.engine.Matcher;
import et.elisa.dra.core.engine.StickyBinding;
import et.elisa.dra.core.engine.ThMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JsonRuleSetLoader {

    private final ObjectMapper mapper;

    public JsonRuleSetLoader() {
        mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("dra-cfg");
        module.addDeserializer(MatcherCfg.class, new MatcherCfgDeserializer());
        module.addSerializer(MatcherCfg.class, new MatcherCfgSerializer());
        module.addDeserializer(ActionCfg.class, new ActionCfgDeserializer());
        module.addSerializer(ActionCfg.class, new ActionCfgSerializer());
        mapper.registerModule(module);
    }

    public RuleSetFile parse(String json) throws IOException {
        return mapper.readValue(json, RuleSetFile.class);
    }

    public String toJson(RuleSetFile file) throws IOException {
        return mapper.writeValueAsString(file);
    }

    static Matcher readMatcher(JsonNode node) throws JsonMappingException {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw bad("matcher must be a non-empty object");
        }
        if (node.hasNonNull("plmnFrom")) {
            List<String> in = stringList(node.get("in"));
            List<String> notIn = stringList(node.get("notIn"));
            if (in.isEmpty() && notIn.isEmpty()) {
                throw bad("plmnFrom requires 'in' or 'notIn' list");
            }
            return Matcher.PlmnMatch.of(text(node, "plmnFrom"), in, notIn);
        }
        String key = node.fieldNames().next();
        JsonNode v = node.get(key);
        return switch (key) {
            case "and" -> {
                List<Matcher> parts = new ArrayList<>();
                requireArray(v, "and");
                for (JsonNode n : v) {
                    parts.add(readMatcher(n));
                }
                yield new Matcher.And(parts);
            }
            case "or" -> {
                List<Matcher> parts = new ArrayList<>();
                requireArray(v, "or");
                for (JsonNode n : v) {
                    parts.add(readMatcher(n));
                }
                yield new Matcher.Or(parts);
            }
            case "not" -> new Matcher.Not(readMatcher(v));
            case "app" -> new Matcher.HasApp(requireInt(v, "app"));
            case "cmd" -> {
                requireArray(v, "cmd");
                List<Integer> codes = new ArrayList<>();
                for (JsonNode n : v) {
                    codes.add(requireInt(n, "cmd"));
                }
                yield new Matcher.HasCmd(codes);
            }
            case "realm" -> Matcher.RealmMatch.of(realmField(v), realmOp(v), text(v, "value"));
            case "host" -> Matcher.HostMatch.of(hostField(v), hostOp(v), text(v, "value"));
            case "avp" -> {
                String op = text(v, "op").toUpperCase(Locale.ROOT).replace('-', '_');
                Matcher.AvpMatch.Op parsed;
                try {
                    parsed = Matcher.AvpMatch.Op.valueOf(op);
                } catch (IllegalArgumentException e) {
                    throw bad("unknown avp op: '" + op
                            + "' (supported EQ PREFIX CONTAINS IN_LIST IP_IN_CIDR)");
                }
                yield Matcher.AvpMatch.of(text(v, "path"), parsed, text(v, "value"));
            }
            case "drmpAtLeast" -> new Matcher.DrmpAtLeast(requireInt(v, "drmpAtLeast"));
            case "ingressPeerIn" -> {
                List<String> ids = stringList(v);
                if (ids.isEmpty()) {
                    throw bad("ingressPeerIn must be a non-empty array");
                }
                yield new Matcher.IngressPeerIn(ids);
            }
            case "flag" -> new Matcher.FlagIs(flagBit(text(node, "flag")));
            case "always" -> {
                if (!v.isBoolean()) {
                    throw bad("'always' must be true or false");
                }
                yield new Matcher.Always(v.asBoolean());
            }
            default -> throw bad("unknown matcher key: '" + key
                    + "' (supported: and or not app cmd realm host avp plmnFrom drmpAtLeast ingressPeerIn flag always)");
        };
    }

    static void writeMatcher(Matcher m, JsonGenerator g) throws IOException {
        if (m instanceof Matcher.And a) {
            g.writeArrayFieldStart("and");
            for (Matcher part : a.parts()) {
                g.writeStartObject();
                writeMatcher(part, g);
                g.writeEndObject();
            }
            g.writeEndArray();
        } else if (m instanceof Matcher.Or o) {
            g.writeArrayFieldStart("or");
            for (Matcher part : o.parts()) {
                g.writeStartObject();
                writeMatcher(part, g);
                g.writeEndObject();
            }
            g.writeEndArray();
        } else if (m instanceof Matcher.Not n) {
            g.writeObjectFieldStart("not");
            writeMatcher(n.inner(), g);
            g.writeEndObject();
        } else if (m instanceof Matcher.HasApp h) {
            g.writeNumberField("app", h.appId());
        } else if (m instanceof Matcher.HasCmd c) {
            g.writeArrayFieldStart("cmd");
            for (int code : c.codes()) {
                g.writeNumber(code);
            }
            g.writeEndArray();
        } else if (m instanceof Matcher.RealmMatch r) {
            g.writeObjectFieldStart("realm");
            g.writeStringField("field", r.field().name());
            g.writeStringField("op", r.op().name());
            g.writeStringField("value", r.value());
            g.writeEndObject();
        } else if (m instanceof Matcher.HostMatch h) {
            g.writeObjectFieldStart("host");
            g.writeStringField("field", h.field().name());
            g.writeStringField("op", h.op().name());
            g.writeStringField("value", h.value());
            g.writeEndObject();
        } else if (m instanceof Matcher.AvpMatch a) {
            g.writeObjectFieldStart("avp");
            g.writeStringField("path", a.path());
            g.writeStringField("op", a.op().name());
            g.writeStringField("value", a.value());
            g.writeEndObject();
        } else if (m instanceof Matcher.PlmnMatch p) {
            g.writeStringField("plmnFrom", p.fromKey());
            if (!p.in().isEmpty()) {
                g.writeArrayFieldStart("in");
                for (String s : p.in()) {
                    g.writeString(s);
                }
                g.writeEndArray();
            }
            if (!p.notIn().isEmpty()) {
                g.writeArrayFieldStart("notIn");
                for (String s : p.notIn()) {
                    g.writeString(s);
                }
                g.writeEndArray();
            }
        } else if (m instanceof Matcher.DrmpAtLeast d) {
            g.writeNumberField("drmpAtLeast", d.threshold());
        } else if (m instanceof Matcher.IngressPeerIn i) {
            g.writeArrayFieldStart("ingressPeerIn");
            for (String id : i.ids()) {
                g.writeString(id);
            }
            g.writeEndArray();
        } else if (m instanceof Matcher.FlagIs f) {
            String letter = switch (f.bit()) {
                case REQUEST -> "R";
                case PROXYABLE -> "P";
                case ERROR -> "E";
                case RETRANSMIT -> "T";
            };
            g.writeStringField("flag", letter);
        } else if (m instanceof Matcher.Always a) {
            g.writeBooleanField("always", a.result());
        } else {
            throw new IOException("unserializable matcher: " + m.getClass().getSimpleName());
        }
    }

    static Action readAction(JsonNode node) throws JsonMappingException {
        if (node == null || !node.isObject()) {
            throw bad("action must be an object");
        }
        if (node.has("forward") && node.hasNonNull("forward")) {
            JsonNode f = node.get("forward");
            if (!f.isObject()) {
                throw bad("'forward' must be an object");
            }
            String group = text(f, "group");
            StickyBinding sticky = null;
            if (f.hasNonNull("sticky") && f.get("sticky").isObject()) {
                JsonNode s = f.get("sticky");
                sticky = new StickyBinding(text(s, "key"), s.path("ttlSecs").asLong(86400L));
            }
            ThMode th = ThMode.OFF;
            if (f.hasNonNull("th")) {
                try {
                    th = ThMode.valueOf(text(f, "th").toUpperCase(Locale.ROOT).replace('-', '_'));
                } catch (IllegalArgumentException e) {
                    throw bad("unknown th mode: '" + f.get("th").asText()
                            + "' (supported OFF PSEUDO_HOST_DETERMINISTIC FULL_EDGE)");
                }
            }
            boolean allowHairpin = f.path("allowHairpin").asBoolean(false);
            List<AvpOp> ops = new ArrayList<>();
            if (f.hasNonNull("ops") && f.get("ops").isArray()) {
                for (JsonNode o : f.get("ops")) {
                    ops.add(readAvpOp(o));
                }
            }
            return new Action.Forward(group, sticky, th, allowHairpin, ops);
        }
        if (node.has("redirect") && node.hasNonNull("redirect")) {
            JsonNode r = node.get("redirect");
            if (!r.isObject()) {
                throw bad("'redirect' must be an object");
            }
            return new Action.Redirect(text(r, "host"), r.path("realm").asText(null),
                    r.path("cacheSecs").asLong(0L));
        }
        if (node.has("reject") && node.hasNonNull("reject")) {
            JsonNode j = node.get("reject");
            if (!j.isObject()) {
                throw bad("'reject' must be an object");
            }
            int code = requireInt(j.get("resultCode"), "resultCode");
            return new Action.Reject(code, j.path("reason").asText(""));
        }
        throw bad("invalid action object: expected one key of forward/redirect/reject");
    }

    static AvpOp readAvpOp(JsonNode node) throws JsonMappingException {
        if (node.hasNonNull("appendRouteRecord") && node.get("appendRouteRecord").isTextual()) {
            return new AvpOp.AppendRouteRecord(node.get("appendRouteRecord").asText());
        }
        if (node.hasNonNull("set") && node.get("set").isObject()) {
            JsonNode s = node.get("set");
            return new AvpOp.Set(s.path("code").asInt(), s.path("vendorId").asInt(0),
                    s.path("typeIndex").asInt(1), s.path("value").asText(""));
        }
        if (node.hasNonNull("drop") && node.get("drop").isObject()) {
            JsonNode d = node.get("drop");
            return new AvpOp.Drop(d.path("code").asInt(), d.path("vendorId").asInt(0));
        }
        throw bad("unsupported avp op entry: expected appendRouteRecord | set | drop");
    }

    private static void requireArray(JsonNode v, String what) throws JsonMappingException {
        if (v == null || !v.isArray()) {
            throw bad("'" + what + "' must be an array");
        }
    }

    private static int requireInt(JsonNode v, String what) throws JsonMappingException {
        if (v == null || !v.canConvertToInt()) {
            throw bad("'" + what + "' must be a JSON number");
        }
        return v.asInt();
    }

    private static String text(JsonNode v, String field) throws JsonMappingException {
        JsonNode n = v.get(field);
        if (n == null || !n.isTextual()) {
            throw bad("'" + field + "' must be a string");
        }
        return n.asText();
    }

    private static List<String> stringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(e -> out.add(e.asText()));
        }
        return out;
    }

    private static Matcher.RealmMatch.Field realmField(JsonNode v) throws JsonMappingException {
        try {
            return Matcher.RealmMatch.Field.valueOf(text(v, "field").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("realm field must be DEST or ORIG");
        }
    }

    private static Matcher.HostMatch.Field hostField(JsonNode v) throws JsonMappingException {
        try {
            return Matcher.HostMatch.Field.valueOf(text(v, "field").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("host field must be DEST or ORIG");
        }
    }

    private static Matcher.RealmMatch.Op realmOp(JsonNode v) throws JsonMappingException {
        try {
            return Matcher.RealmMatch.Op.valueOf(text(v, "op").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("realm op must be EQ, SUFFIX or REGEX");
        }
    }

    private static Matcher.HostMatch.Op hostOp(JsonNode v) throws JsonMappingException {
        try {
            return Matcher.HostMatch.Op.valueOf(text(v, "op").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("host op must be EQ, SUFFIX or REGEX");
        }
    }

    private static Matcher.FlagIs.Bit flagBit(String s) throws JsonMappingException {
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "R", "REQUEST" -> Matcher.FlagIs.Bit.REQUEST;
            case "P", "PROXYABLE" -> Matcher.FlagIs.Bit.PROXYABLE;
            case "E", "ERROR" -> Matcher.FlagIs.Bit.ERROR;
            case "T", "RETRANSMIT" -> Matcher.FlagIs.Bit.RETRANSMIT;
            default -> throw bad("flag must be R, P, E or T");
        };
    }

    static final class MatcherCfgDeserializer extends JsonDeserializer<MatcherCfg> {

        @Override
        public MatcherCfg deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return new MatcherCfg(readMatcher(p.readValueAsTree()));
        }
    }

    static final class MatcherCfgSerializer extends JsonSerializer<MatcherCfg> {

        @Override
        public void serialize(MatcherCfg value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeStartObject();
            writeMatcher(value.matcher(), gen);
            gen.writeEndObject();
        }
    }

    static final class ActionCfgDeserializer extends JsonDeserializer<ActionCfg> {

        @Override
        public ActionCfg deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return new ActionCfg(readAction(p.readValueAsTree()));
        }
    }

    static final class ActionCfgSerializer extends JsonSerializer<ActionCfg> {

        @Override
        public void serialize(ActionCfg value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeStartObject();
            writeAction(value.action(), gen);
            gen.writeEndObject();
        }
    }

    static void writeAction(Action a, JsonGenerator g) throws IOException {
        if (a instanceof Action.Forward f) {
            g.writeObjectFieldStart("forward");
            g.writeStringField("group", f.group());
            if (f.sticky() != null) {
                g.writeObjectFieldStart("sticky");
                g.writeStringField("key", f.sticky().key());
                g.writeNumberField("ttlSecs", f.sticky().ttlSeconds());
                g.writeEndObject();
            }
            if (f.th() != null && f.th() != ThMode.OFF) {
                g.writeStringField("th", f.th().name());
            }
            if (f.allowHairpin()) {
                g.writeBooleanField("allowHairpin", true);
            }
            if (!f.ops().isEmpty()) {
                g.writeArrayFieldStart("ops");
                for (AvpOp op : f.ops()) {
                    writeAvpOp(op, g);
                }
                g.writeEndArray();
            }
            g.writeEndObject();
        } else if (a instanceof Action.Redirect r) {
            g.writeObjectFieldStart("redirect");
            g.writeStringField("host", r.host());
            if (r.realm() != null) {
                g.writeStringField("realm", r.realm());
            }
            g.writeNumberField("cacheSecs", r.cacheSeconds());
            g.writeEndObject();
        } else if (a instanceof Action.Reject j) {
            g.writeObjectFieldStart("reject");
            g.writeNumberField("resultCode", j.resultCode());
            g.writeStringField("reason", j.reason());
            g.writeEndObject();
        } else {
            throw new IOException("unserializable action: " + a.getClass().getSimpleName());
        }
    }

    static void writeAvpOp(AvpOp op, JsonGenerator g) throws IOException {
        if (op instanceof AvpOp.AppendRouteRecord a) {
            g.writeStartObject();
            g.writeStringField("appendRouteRecord", a.host());
            g.writeEndObject();
        } else if (op instanceof AvpOp.Set s) {
            g.writeStartObject();
            g.writeObjectFieldStart("set");
            g.writeNumberField("code", s.code());
            g.writeNumberField("vendorId", s.vendorId());
            g.writeNumberField("typeIndex", s.typeIndex());
            g.writeStringField("value", s.value());
            g.writeEndObject();
            g.writeEndObject();
        } else if (op instanceof AvpOp.Drop d) {
            g.writeStartObject();
            g.writeObjectFieldStart("drop");
            g.writeNumberField("code", d.code());
            g.writeNumberField("vendorId", d.vendorId());
            g.writeEndObject();
            g.writeEndObject();
        }
    }

    static JsonMappingException bad(String msg) {
        return new JsonMappingException((JsonParser) null, msg);
    }
}
