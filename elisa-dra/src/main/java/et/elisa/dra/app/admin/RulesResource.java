package et.elisa.dra.app.admin;

import et.elisa.dra.app.persist.AuditRecorder;
import et.elisa.dra.app.persist.RouteConfigSink;
import et.elisa.dra.core.cfg.RuleSetHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/rules")
@ApplicationScoped
public class RulesResource {

    private final RuleSetHolder holder;
    private final RouteConfigSink sink;
    private final AuditRecorder audit;

    @Inject
    public RulesResource(RuleSetHolder holder,
                         Instance<RouteConfigSink> sinks,
                         Instance<AuditRecorder> audits) {
        this.holder = holder;
        this.sink = sinks.isResolvable() ? sinks.get() : null;
        this.audit = audits.isResolvable() ? audits.get() : null;
    }

    public RulesResource(RuleSetHolder holder) {
        this(holder, (RouteConfigSink) null, (AuditRecorder) null);
    }

    public RulesResource(RuleSetHolder holder, RouteConfigSink sink, AuditRecorder audit) {
        this.holder = holder;
        this.sink = sink;
        this.audit = audit;
    }

    public RulesResource() {
        this(new RuleSetHolder());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response current() {
        return Response.ok(holder.currentJson()).build();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response apply(String jsonBody) {
        List<String> errors = holder.applyCandidate(jsonBody == null ? "" : jsonBody);
        if (errors.isEmpty()) {
            boolean persisted = persistApplied(holder.version(), jsonBody == null ? "" : jsonBody);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("applied", true);
            ok.put("version", holder.version());
            ok.put("persisted", persisted);
            return Response.ok(ok).build();
        }
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("applied", false);
        bad.put("errors", errors);
        bad.put("lastGoodVersion", holder.version());
        return Response.status(Response.Status.BAD_REQUEST).entity(bad).build();
    }

    private boolean persistApplied(int version, String json) {
        if (sink == null || !sink.durable()) {
            return false;
        }
        try {
            sink.persistApplied(version, json);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
