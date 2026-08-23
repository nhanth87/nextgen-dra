package et.elisa.dra.lab.testapp;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

import et.elisa.dra.lab.testapp.web.Json;

public final class Metrics {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final MessageLog log;

    public Metrics(MessageLog log) {
        this.log = log;
    }

    public Map<String, Object> snapshot() {
        Runtime runtime = Runtime.getRuntime();
        long[] deadlocked = THREADS.findDeadlockedThreads();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("heapUsed", Math.max(0L, runtime.totalMemory() - runtime.freeMemory()));
        out.put("heapMax", runtime.maxMemory());
        out.put("threadCount", (long) THREADS.getThreadCount());
        out.put("deadlockCount", deadlocked == null ? 0L : (long) deadlocked.length);
        out.put("requestsTotal", log.requestsTotal());
        out.put("answersTotal", log.answersTotal());
        out.put("errorsTotal", log.errorsTotal());
        out.put("lastMessageAgeMillis", log.lastMessageAgeMillis());
        return out;
    }

    public String toJson() {
        return Json.objectOf(snapshot());
    }
}
