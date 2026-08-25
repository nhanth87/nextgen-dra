package et.elisa.dra.app.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/peers")
@ApplicationScoped
public class PeersResource {

    private final AdminPort port;

    @Inject
    public PeersResource(AdminPort port) {
        this.port = port;
    }

    public PeersResource() {
        this(AdminPort.NOOP);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> peers() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", port.live());
        out.put("peers", port.peersHealth());
        return out;
    }

    @POST
    @Path("/{id}/enable")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> enable(@PathParam("id") String id) {
        return result(id, port.enablePeer(id), true);
    }

    @POST
    @Path("/{id}/disable")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> disable(@PathParam("id") String id) {
        return result(id, port.disablePeer(id), false);
    }

    private static Map<String, Object> result(String id, boolean applied, boolean targetState) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("peer", id);
        out.put("targetState", targetState ? "enabled" : "disabled");
        out.put("applied", applied);
        return out;
    }
}
