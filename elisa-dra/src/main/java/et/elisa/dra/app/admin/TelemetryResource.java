package et.elisa.dra.app.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/telemetry")
@ApplicationScoped
public class TelemetryResource {

    private final TelemetryPort port;

    @Inject
    public TelemetryResource(TelemetryPort port) {
        this.port = port;
    }

    public TelemetryResource() {
        this(TelemetryPort.NOOP);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> telemetry() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", port.live());
        out.put("counters", port.snapshot());
        return out;
    }
}
