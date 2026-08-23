package et.elisa.dra.app.admin;

import java.util.Map;

public interface TelemetryPort {

    boolean live();

    Map<String, Long> snapshot();

    TelemetryPort NOOP = new TelemetryPort() {

        @Override
        public boolean live() {
            return false;
        }

        @Override
        public Map<String, Long> snapshot() {
            return Map.of();
        }
    };
}
