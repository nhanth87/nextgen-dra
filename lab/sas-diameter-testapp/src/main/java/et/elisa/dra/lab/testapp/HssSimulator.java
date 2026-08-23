package et.elisa.dra.lab.testapp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class HssSimulator {

    public static final String DEMO_IMSI = "655010000000001";
    public static final String DEMO_MSISDN = "+251911111111";

    private final MessageLog messageLog;
    private final Map<String, SubscriberState> byImsi = new LinkedHashMap<>();
    private final Map<String, SubscriberState> byMsisdn = new LinkedHashMap<>();
    private final BindingRegistry bindings = new BindingRegistry();

    public HssSimulator(MessageLog messageLog) {
        this.messageLog = messageLog;
        SubscriberState demo = new SubscriberState(DEMO_IMSI, DEMO_MSISDN);
        byImsi.put(DEMO_IMSI, demo);
        byMsisdn.put(DEMO_MSISDN, demo);
    }

    public Optional<SubscriberState> find(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String key = username.trim();
        String normalized = key.startsWith("+") ? key : "+" + key;
        SubscriberState state = byImsi.get(key);
        if (state != null) {
            return Optional.of(state);
        }
        state = byMsisdn.get(key);
        if (state != null) {
            return Optional.of(state);
        }
        return Optional.ofNullable(byMsisdn.get(normalized));
    }

    public synchronized SubscriberState upsert(String imsi, String msisdn) {
        SubscriberState existing = byImsi.get(imsi);
        if (existing != null) {
            if (!existing.msisdn().equals(msisdn)) {
                byMsisdn.remove(existing.msisdn());
                existing.rebindMsisdn(msisdn);
                byMsisdn.put(msisdn, existing);
            }
            return existing;
        }
        SubscriberState fresh = new SubscriberState(imsi, msisdn);
        byImsi.put(imsi, fresh);
        byMsisdn.put(msisdn, fresh);
        return fresh;
    }

    public Collection<SubscriberState> subscribers() {
        return byImsi.values();
    }

    public static String defaultMsisdn(String identity) {
        String digits = identity == null ? "" : identity.replaceAll("\\D", "");
        if (digits.length() >= 9) {
            return "+251" + digits.substring(digits.length() - 9);
        }
        return "+" + digits;
    }

    public MessageLog log() {
        return messageLog;
    }

    public BindingRegistry bindings() {
        return bindings;
    }

    public synchronized void reset() {
        messageLog.clear();
        byImsi.values().forEach(SubscriberState::resetDefaults);
        bindings.clear();
        bindings.upsert(BindingRegistry.DEMO_IP, DEMO_MSISDN, DEMO_IMSI);
    }

    public boolean isKnownUser(String username) {
        return find(username).isPresent();
    }
}
