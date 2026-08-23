package et.elisa.dra.lab.testapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;

import et.elisa.dra.lab.testapp.web.Json;

public final class ExitReason {

    public static final String SHUTDOWN = "shutdown";

    private ExitReason() {
    }

    public static void write(Path file, String reason) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = Json.objectOf(Map.of(
                "exitReason", reason == null ? "unknown" : reason,
                "timestamp", Instant.now().toString()));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
