package et.elisa.dra.lab.testapp.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import et.elisa.dra.lab.testapp.BindingRegistry;
import et.elisa.dra.lab.testapp.HssSimulator;
import et.elisa.dra.lab.testapp.MessageLog;
import et.elisa.dra.lab.testapp.Metrics;
import et.elisa.dra.lab.testapp.SubscriberState;
import et.elisa.dra.lab.testapp.diameter.HssDiameterServer;

public final class ControlWebServer {

    private static final Logger LOG = LogManager.getLogger(ControlWebServer.class);

    public record ApiResponse(int status, String contentType, String body) {
    }

    private final HssSimulator hss;
    private final HssDiameterServer diameter;
    private HttpServer server;

    public ControlWebServer(HssSimulator hss, HssDiameterServer diameter) {
        this.hss = hss;
        this.diameter = diameter;
    }

    public void start(String bindAddress, int webPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindAddress, webPort), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        LOG.info("Control UI listening on http://{}:{}/", bindAddress, webPort);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = readBody(exchange, method);
        ApiResponse response = handle(method, path, body);
        respond(exchange, response.status(), response.contentType(), response.body());
        exchange.close();
    }

    public ApiResponse handle(String method, String path, String body) {
        try {
            if (path.startsWith("/api/binding/")) {
                return bindingDelete(method, path.substring("/api/binding/".length()));
            }
            return switch (path) {
                case "/", "/index.html" ->
                    new ApiResponse(200, "text/html; charset=utf-8", Pages.index());
                case "/api/messages" -> messages(method);
                case "/api/subscriber" -> subscriber(method, body);
                case "/api/binding" -> binding(method, body);
                case "/api/reset" -> reset(method);
                case "/api/health" -> health(method);
                case "/api/metrics" -> metrics(method);
                default -> new ApiResponse(404, "application/json", Json.objectOf(
                        Map.of("error", "not found: " + path)));
            };
        } catch (BadRequest e) {
            return new ApiResponse(400, "application/json",
                    Json.objectOf(Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage())));
        } catch (Exception e) {
            LOG.warn("control API failure {} {}", method, path, e);
            return new ApiResponse(500, "application/json",
                    Json.objectOf(Map.of("error", "internal error")));
        }
    }

    private static String readBody(HttpExchange exchange, String method) throws IOException {
        if ("POST".equals(method) || "PUT".equals(method)) {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }
        return "";
    }

    private ApiResponse messages(String method) {
        requireMethod(method, "GET");
        List<Map<String, Object>> items = new ArrayList<>();
        for (MessageLog.Entry entry : hss.log().snapshot()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", entry.time().toString());
            row.put("direction", entry.direction());
            row.put("command", entry.command());
            row.put("session", entry.sessionId());
            row.put("result", entry.result());
            row.put("details", entry.details());
            items.add(row);
        }
        return new ApiResponse(200, "application/json", Json.arrayOf(items));
    }

    private ApiResponse subscriber(String method, String body) {
        if ("GET".equals(method)) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (SubscriberState state : hss.subscribers()) {
                items.add(subscriberFields(state));
            }
            return new ApiResponse(200, "application/json",
                    "{\"subscribers\":" + Json.arrayOf(items) + "}");
        }
        requireMethod(method, "POST");
        Map<String, Object> update = Json.parseFlatObject(body);
        Object identity = update.get("identity");
        if (identity == null || identity.toString().isBlank()) {
            throw new BadRequest("identity (IMSI or MSISDN) is required");
        }
        SubscriberState state = hss.find(identity.toString()).orElse(null);
        boolean created = state == null;
        if (state == null) {
            state = create(identity.toString(), update.get("msisdn"));
        } else {
            Object msisdn = update.get("msisdn");
            if (msisdn != null && !msisdn.toString().isBlank()
                    && !msisdn.toString().equals(state.msisdn())) {
                state = hss.upsert(state.imsi(), msisdn.toString());
            }
        }
        applyFieldUpdates(state, update);
        Map<String, Object> fields = subscriberFields(state);
        fields.put("created", created);
        return new ApiResponse(200, "application/json", Json.objectOf(fields));
    }

    private SubscriberState create(String identity, Object requestedMsisdn) {
        String digitsOnly = identity.replaceAll("\\D", "");
        if (digitsOnly.length() < 6 || digitsOnly.length() != identity.length()) {
            throw new BadRequest(
                    "unknown identity " + identity + " (create needs a numeric IMSI)");
        }
        String msisdn = requestedMsisdn == null || requestedMsisdn.toString().isBlank()
                ? HssSimulator.defaultMsisdn(digitsOnly) : requestedMsisdn.toString();
        return hss.upsert(digitsOnly, msisdn);
    }

    private static void applyFieldUpdates(SubscriberState state, Map<String, Object> update) {
        if (update.containsKey("attached")) {
            state.setAttached(bool(update.get("attached"), "attached"));
        }
        if (update.containsKey("barred")) {
            state.setBarred(bool(update.get("barred"), "barred"));
        }
        if (update.containsKey("authVectorsAvailable")) {
            Object value = update.get("authVectorsAvailable");
            if (!(value instanceof Number number) || number.intValue() < 0) {
                throw new BadRequest("authVectorsAvailable must be a non-negative number");
            }
            state.setAuthVectorsAvailable(number.intValue());
        }
        if (update.containsKey("subscribedRat")) {
            Object value = update.get("subscribedRat");
            if (value == null || value.toString().isBlank()) {
                throw new BadRequest("subscribedRat must not be blank");
            }
            state.setSubscribedRat(value.toString());
        }
    }

    private ApiResponse binding(String method, String body) {
        if ("GET".equals(method)) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (BindingRegistry.Binding b : hss.bindings().list()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ip", b.ip());
                row.put("msisdn", b.msisdn());
                row.put("imsi", b.imsi());
                items.add(row);
            }
            return new ApiResponse(200, "application/json",
                    "{\"bindings\":" + Json.arrayOf(items) + "}");
        }
        requireMethod(method, "POST");
        Map<String, Object> update = Json.parseFlatObject(body);
        Object ip = update.get("ip");
        if (ip == null || ip.toString().isBlank()) {
            throw new BadRequest("ip is required");
        }
        if (boolOrFalse(update.get("clear"))) {
            BindingRegistry.Binding removed = hss.bindings().remove(ip.toString());
            return new ApiResponse(200, "application/json", Json.objectOf(Map.of(
                    "removed", removed != null,
                    "ip", ip.toString())));
        }
        Object msisdn = update.get("msisdn");
        Object imsi = update.get("imsi");
        if (msisdn == null || msisdn.toString().isBlank()) {
            throw new BadRequest("msisdn is required (or clear=true to remove)");
        }
        BindingRegistry.Binding bound = hss.bindings().upsert(ip.toString(), msisdn.toString(),
                imsi == null ? null : imsi.toString());
        return new ApiResponse(200, "application/json", Json.objectOf(Map.of(
                "ip", bound.ip(),
                "msisdn", bound.msisdn(),
                "imsi", bound.imsi() == null ? "" : bound.imsi())));
    }

    private ApiResponse bindingDelete(String method, String ip) {
        requireMethod(method, "DELETE");
        BindingRegistry.Binding removed = hss.bindings().remove(ip);
        return new ApiResponse(200, "application/json", Json.objectOf(Map.of(
                "removed", removed != null,
                "ip", ip)));
    }

    private ApiResponse reset(String method) {
        requireMethod(method, "POST");
        hss.reset();
        return new ApiResponse(200, "application/json",
                Json.objectOf(Map.of("reset", true)));
    }

    private ApiResponse health(String method) {
        requireMethod(method, "GET");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "up");
        fields.put("diameterListening", diameter.isListening());
        fields.put("lastMessageAgeMillis", hss.log().lastMessageAgeMillis());
        return new ApiResponse(200, "application/json", Json.objectOf(fields));
    }

    private ApiResponse metrics(String method) {
        requireMethod(method, "GET");
        return new ApiResponse(200, "application/json", new Metrics(hss.log()).toJson());
    }

    private static Map<String, Object> subscriberFields(SubscriberState state) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("imsi", state.imsi());
        fields.put("msisdn", state.msisdn());
        fields.put("attached", state.attached());
        fields.put("barred", state.barred());
        fields.put("authVectorsAvailable", state.authVectorsAvailable());
        fields.put("subscribedRat", state.subscribedRat());
        long lastEap = state.lastEapAuthSuccess();
        fields.put("lastEapAuthSuccess", lastEap <= 0 ? null : Instant.ofEpochMilli(lastEap).toString());
        return fields;
    }

    private static boolean boolOrFalse(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof String s && s.equalsIgnoreCase("true");
    }

    private static boolean bool(Object value, String field) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(s);
        }
        throw new BadRequest(field + " must be a boolean");
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BadRequest(expected + " only");
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType,
            String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }
}
