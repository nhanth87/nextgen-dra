package et.elisa.dra.lab.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import et.elisa.dra.lab.testapp.diameter.HssDiameterServer;
import et.elisa.dra.lab.testapp.web.ControlWebServer;
import et.elisa.dra.lab.testapp.web.Json;

class ApiHandleTest {

    private HssSimulator hss;
    private ControlWebServer api;

    @BeforeEach
    void setUp() {
        hss = new HssSimulator(new MessageLog());
        HssDiameterServer diameter = new HssDiameterServer(hss, "127.0.0.1", 3869, true,
                Config.ORIGIN_HOST, Config.ORIGIN_REALM,
                Config.DEFAULT_PEER_HOST, Config.ORIGIN_REALM);
        api = new ControlWebServer(hss, diameter);
    }

    @Test
    void metricsEndpointShape() {
        ControlWebServer.ApiResponse response = api.handle("GET", "/api/metrics", "");
        assertEquals(200, response.status());
        assertEquals("application/json", response.contentType());
        Map<String, Object> body = Json.parseFlatObject(response.body());
        for (String key : List.of("heapUsed", "heapMax", "threadCount", "deadlockCount",
                "requestsTotal", "answersTotal", "errorsTotal", "lastMessageAgeMillis")) {
            assertTrue(body.containsKey(key), "missing " + key + " in " + body);
        }
    }

    @Test
    void healthCarriesListeningAndMessageAge() {
        ControlWebServer.ApiResponse response = api.handle("GET", "/api/health", "");
        assertEquals(200, response.status());
        Map<String, Object> body = Json.parseFlatObject(response.body());
        assertEquals(Boolean.FALSE, body.get("diameterListening"));
        assertTrue(body.containsKey("lastMessageAgeMillis"));
    }

    @Test
    void subscriberPostCreatesNewImsi() {
        ControlWebServer.ApiResponse response = api.handle("POST", "/api/subscriber",
                "{\"identity\":\"452099900000009\",\"attached\":false}");
        assertEquals(200, response.status());
        Map<String, Object> body = Json.parseFlatObject(response.body());
        assertEquals(Boolean.TRUE, body.get("created"));
        SubscriberState state = hss.find("452099900000009").orElseThrow();
        assertFalse(state.attached());
        assertEquals("+251900000009", state.msisdn());
    }

    @Test
    void subscriberPostUpdatesExistingWithoutCreating() {
        api.handle("POST", "/api/subscriber", "{\"identity\":\"452040200000001\"}");
        ControlWebServer.ApiResponse second = api.handle("POST", "/api/subscriber",
                "{\"identity\":\"452040200000001\",\"barred\":true}");
        Map<String, Object> body = Json.parseFlatObject(second.body());
        assertEquals(Boolean.FALSE, body.get("created"));
        assertTrue(hss.find("452040200000001").orElseThrow().barred());
    }

    @Test
    void invalidSubscriberPostsAreRejected() {
        assertEquals(400, api.handle("POST", "/api/subscriber", "{}").status());
        assertEquals(400, api.handle("POST", "/api/subscriber", "{\"identity\":\"not-imsi\"}").status());
        assertEquals(400, api.handle("POST", "/api/metrics", "").status());
        assertEquals(404, api.handle("GET", "/api/nothing", "").status());
    }
}
