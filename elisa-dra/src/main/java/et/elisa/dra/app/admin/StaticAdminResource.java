package et.elisa.dra.app.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
/**
 * Serves the htmx admin hub straight from disk (house style: static UI lives
 * outside the jar, packaged under app/ and never clobbered by upgrades).
 * Resolution order mirrors configs/: ./app/admin then ../app/admin.
 */
@Path("/admin")
@ApplicationScoped
public class StaticAdminResource {

    private static final List<String> BASES = List.of("app/admin", "html/admin", "../app/admin");

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response index() {
        return serve("index.html");
    }

    @GET
    @Path("/{file}")
    public Response serve(@PathParam("file") String file) {
        if (file == null || file.isBlank() || file.contains("..") || file.contains("/")
                || file.contains("\\")) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        for (String base : BASES) {
            java.nio.file.Path p = java.nio.file.Path.of(base, file);
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                String type = contentType(file);
                return Response.ok(Files.readString(p), type).build();
            } catch (IOException e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        }
        return Response.status(Response.Status.NOT_FOUND)
                .entity("<span class=\"muted\">admin page not found: " + file
                        + " (looked in: app/admin)</span>")
                .type(MediaType.TEXT_HTML)
                .build();
    }

    private static String contentType(String file) {
        if (file.endsWith(".html")) {
            return MediaType.TEXT_HTML;
        }
        if (file.endsWith(".css")) {
            return "text/css";
        }
        if (file.endsWith(".js")) {
            return "application/javascript";
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
