package et.elisa.dra.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BenchScenario {

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String host = opts.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(opts.getOrDefault("port", "3868"));
        double tps = Double.parseDouble(opts.getOrDefault("tps", "1000"));
        int durationS = Integer.parseInt(opts.getOrDefault("duration-s", "60"));
        int connections = Integer.parseInt(opts.getOrDefault("connections", "4"));
        String imsiPrefix = opts.getOrDefault("imsi-prefix", "4520402");
        long timeoutMs = Long.parseLong(opts.getOrDefault("timeout-ms", "5000"));
        int srcPort = Integer.parseInt(opts.getOrDefault("src-port", "0"));
        String destHost = opts.getOrDefault("dest-host", "");

        int count = (int) (tps * durationS);
        try (SeederClient client = new SeederClient(host, port, srcPort, connections,
                tps, timeoutMs, imsiPrefix, destHost)) {
            SeederClient.Stats stats = client.run(count);
            print(stats, tps, durationS);
            writeReport(stats, tps, durationS, connections);
        }
    }

    private static void print(SeederClient.Stats stats, double tps, int durationS) {
        System.out.println("=== Nextgen DRA bench ===");
        System.out.printf("target      : %.0f TPS x %ds%n", tps, durationS);
        System.out.printf("sent        : %d%n", stats.sent());
        System.out.printf("received    : %d (%.3f%% loss)%n", stats.received(), lossPct(stats));
        System.out.printf("timeouts    : %d%n", stats.timeouts());
        System.out.printf("p50 / p90 / p99 / max : %.2f / %.2f / %.2f / %.2f ms%n",
                stats.p50Nanos() / 1e6, stats.p90Nanos() / 1e6,
                stats.p99Nanos() / 1e6, stats.maxNanos() / 1e6);
        System.out.printf("elapsed     : %d ms%n", stats.elapsedMillis());
    }

    private static void writeReport(SeederClient.Stats stats, double tps,
                                    int durationS, int connections) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("targetTps", tps);
        report.put("durationSeconds", durationS);
        report.put("connections", connections);
        report.put("sent", stats.sent());
        report.put("received", stats.received());
        report.put("timeouts", stats.timeouts());
        report.put("lossPercent", lossPct(stats));
        report.put("p50Nanos", stats.p50Nanos());
        report.put("p90Nanos", stats.p90Nanos());
        report.put("p99Nanos", stats.p99Nanos());
        report.put("maxNanos", stats.maxNanos());
        report.put("elapsedMillis", stats.elapsedMillis());
        Files.writeString(Path.of("bench-report.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static double lossPct(SeederClient.Stats stats) {
        if (stats.sent() == 0) {
            return 0;
        }
        return (stats.sent() - stats.received()) * 100.0 / stats.sent();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            String key = args[i].startsWith("--") ? args[i].substring(2) : args[i];
            opts.put(key, args[i + 1]);
        }
        return opts;
    }
}
