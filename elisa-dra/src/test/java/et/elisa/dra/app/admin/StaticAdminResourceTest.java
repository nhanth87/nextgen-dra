package et.elisa.dra.app.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StaticAdminResourceTest {

    private final StaticAdminResource resource = new StaticAdminResource();

    @Test
    void servesIndexFromRepoRootAppDir() {
        var response = resource.index();
        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("Nextgen DRA"));
    }

    @Test
    void servesCssAndJs() {
        assertEquals(200, resource.serve("theme.css").getStatus());
        assertEquals(200, resource.serve("app.js").getStatus());
    }

    @Test
    void rejectsTraversalAndUnknownFiles() {
        assertEquals(404, resource.serve("../pom.xml").getStatus());
        assertEquals(404, resource.serve("nope.html").getStatus());
        assertEquals(404, resource.serve(null).getStatus());
    }
}
