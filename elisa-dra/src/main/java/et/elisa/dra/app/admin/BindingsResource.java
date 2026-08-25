package et.elisa.dra.app.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/bindings")
@ApplicationScoped
public class BindingsResource {

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
}
