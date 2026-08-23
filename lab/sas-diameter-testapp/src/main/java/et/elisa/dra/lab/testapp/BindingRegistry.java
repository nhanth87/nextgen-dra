package et.elisa.dra.lab.testapp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BindingRegistry {

    public static final String DEMO_IP = "10.20.30.40";

    public record Binding(String ip, String msisdn, String imsi) {}

    private final ConcurrentHashMap<String, Binding> byIp = new ConcurrentHashMap<>();

    public BindingRegistry() {
        upsert(DEMO_IP, HssSimulator.DEMO_MSISDN, HssSimulator.DEMO_IMSI);
    }

    public synchronized Binding upsert(String ip, String msisdn, String imsi) {
        Binding fresh = new Binding(ip, msisdn, imsi);
        byIp.put(ip, fresh);
        return fresh;
    }

    public Binding find(String ip) {
        return ip == null ? null : byIp.get(ip);
    }

    public Binding remove(String ip) {
        return ip == null ? null : byIp.remove(ip);
    }

    public Collection<Binding> list() {
        Map<String, Binding> ordered = new LinkedHashMap<>();
        byIp.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((a, b) -> a.ip().compareTo(b.ip())))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered.values();
    }

    public int size() {
        return byIp.size();
    }

    public void clear() {
        byIp.clear();
    }
}
