package et.elisa.dra.lab.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import et.elisa.dra.lab.testapp.web.Json;

class ExitReasonTest {

    @TempDir
    Path tmp;

    @Test
    void writesParseableExitReasonJson() throws Exception {
        Path file = tmp.resolve("testapp-exit.json");
        ExitReason.write(file, ExitReason.SHUTDOWN);
        assertTrue(Files.exists(file));
        Map<String, Object> parsed = Json.parseFlatObject(Files.readString(file));
        assertEquals("shutdown", parsed.get("exitReason"));
        assertDoesNotThrow(() -> Instant.parse(parsed.get("timestamp").toString()));
    }

    @Test
    void overwritesPreviousStatusFile() throws Exception {
        Path file = tmp.resolve("nested").resolve("exit.json");
        ExitReason.write(file, "first");
        ExitReason.write(file, "second");
        Map<String, Object> parsed = Json.parseFlatObject(Files.readString(file));
        assertEquals("second", parsed.get("exitReason"));
    }

    private static void assertDoesNotThrow(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            throw new AssertionError("unexpected exception", e);
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
