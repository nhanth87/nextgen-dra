package et.elisa.dra.lab.testapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import et.elisa.dra.lab.testapp.web.Json;

public final class SubscriberSeeds {

    public record Spec(String imsi, String msisdn, Boolean attached, Boolean barred,
            Integer authVectorsAvailable, String subscribedRat) {
    }

    private SubscriberSeeds() {
    }

    public static List<Spec> labDefaults() {
        return List.of(
                new Spec("4520402000000001", "+2519110000001", Boolean.TRUE, Boolean.FALSE, 1, "EUTRAN"),
                new Spec("4520402000000002", "+2519110000002", Boolean.TRUE, Boolean.TRUE, 1, "EUTRAN"),
                new Spec("4520402000000003", "+2519110000003", Boolean.FALSE, Boolean.FALSE, 1, "EUTRAN"),
                new Spec("4520402000000004", "+2519110000004", Boolean.TRUE, Boolean.FALSE, 0, "EUTRAN"),
                new Spec("4520409990000001", "+2519110000005", Boolean.TRUE, Boolean.FALSE, 1, "EUTRAN"));
    }

    public static List<Spec> parse(String content) {
        List<Spec> out = new ArrayList<>();
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            Map<String, Object> fields = Json.parseFlatObject(line);
            Object imsi = fields.get("imsi");
            if (imsi == null || imsi.toString().isBlank()) {
                throw new IllegalArgumentException("seed line missing imsi: " + line);
            }
            out.add(new Spec(imsi.toString(), str(fields.get("msisdn")),
                    bool(fields.get("attached")), bool(fields.get("barred")),
                    intOf(fields.get("authVectorsAvailable")), str(fields.get("subscribedRat"))));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no subscriber seeds parsed");
        }
        return out;
    }

    public static void apply(HssSimulator hss, List<Spec> specs) {
        for (Spec spec : specs) {
            String msisdn = spec.msisdn() == null || spec.msisdn().isBlank()
                    ? HssSimulator.defaultMsisdn(spec.imsi()) : spec.msisdn();
            SubscriberState state = hss.upsert(spec.imsi(), msisdn);
            if (spec.attached() != null) {
                state.setAttached(spec.attached());
            }
            if (spec.barred() != null) {
                state.setBarred(spec.barred());
            }
            if (spec.authVectorsAvailable() != null) {
                state.setAuthVectorsAvailable(spec.authVectorsAvailable());
            }
            if (spec.subscribedRat() != null && !spec.subscribedRat().isBlank()) {
                state.setSubscribedRat(spec.subscribedRat());
            }
        }
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    private static Integer intOf(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
