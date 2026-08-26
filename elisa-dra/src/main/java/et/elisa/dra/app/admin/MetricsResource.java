package et.elisa.dra.app.admin;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/metrics")
@ApplicationScoped
public class MetricsResource {

    private final PrometheusMeterRegistry registry;

    @Inject
    public MetricsResource(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    public MetricsResource() {
        this(new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT));
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String scrape() {
        return registry.scrape();
    }
}
