package et.elisa.dra.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchSmokeTest {

    @Test
    void seederAgainstFakeHssZeroLoss() throws Exception {
        try (FakeHssServer server = new FakeHssServer(0, 0, 0)) {
            server.start();
            try (SeederClient client = new SeederClient("127.0.0.1", server.port(),
                    1, 500, 2000, "4520402")) {
                SeederClient.Stats stats = client.run(200);
                assertEquals(0, server.requests() >= 200 ? 0 : 1,
                        "server saw fewer requests than sent");
                assertEquals(0, stats.timeouts(), "timeouts: " + stats);
                assertEquals(stats.sent(), stats.received(),
                        "loss detected: " + stats);
                assertTrue(stats.p99Nanos() < 250_000_000L,
                        "p99 too high: " + stats.p99Nanos() / 1e6 + "ms");
            }
        }
    }
}
