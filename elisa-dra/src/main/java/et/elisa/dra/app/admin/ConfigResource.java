package et.elisa.dra.app.admin;

import et.elisa.dra.core.cfg.RuleSetHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/config")
@ApplicationScoped
public class ConfigResource {

    private final AdminPort admin;
    private final RuleSetHolder rules;

    @Inject
    public ConfigResource(AdminPort admin, RuleSetHolder rules) {
        this.admin = admin;
        this.rules = rules;
    }

    public ConfigResource() {
        this(AdminPort.NOOP, new RuleSetHolder());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>(admin.runtimeConfig());
        out.put("rulesVersion", rules.version());
        return out;
    }
}
