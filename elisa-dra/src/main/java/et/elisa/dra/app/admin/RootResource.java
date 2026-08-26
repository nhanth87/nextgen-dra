package et.elisa.dra.app.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/** Entry point: send browsers to the admin hub. */
@Path("/")
@ApplicationScoped
public class RootResource {

    @GET
    public Response root() {
        return Response.temporaryRedirect(URI.create("/admin/index.html")).build();
    }
}
