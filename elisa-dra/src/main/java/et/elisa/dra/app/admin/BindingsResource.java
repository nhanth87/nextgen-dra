package et.elisa.dra.app.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/bindings")
@ApplicationScoped
public class BindingsResource {

    private static final int MAX_SAMPLE = 100;

    private final AdminPort port;

    @Inject
    public BindingsResource(AdminPort port) {
        this.port = port;
    }

    public BindingsResource() {
        this(AdminPort.NOOP);
    }

    @GET
    @Path("/count")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> count() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", port.bindingsCount());
        out.put("live", port.live());
        return out;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> list(@QueryParam("limit") @DefaultValue("20") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", port.bindingsCount());
        out.put("live", port.live());
        out.put("entries", port.bindingsSample(Math.min(Math.max(limit, 0), MAX_SAMPLE)));
        return out;
    }
}
