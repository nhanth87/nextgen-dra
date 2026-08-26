package et.elisa.dra.app.persist;

import et.elisa.dra.core.cfg.RuleSetHolder;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Restores the applied rule set at boot: latest durable payload from
 * route_config wins; configs/dra-rules.json is only a first-boot seed.
 */
@ApplicationScoped
public class RulesBootLoader {

    private static final Logger LOG = LogManager.getLogger(RulesBootLoader.class);

    private final RuleSetHolder holder;
    private final RouteConfigSink sink;

    @Inject
    public RulesBootLoader(RuleSetHolder holder, Instance<RouteConfigSink> sinks) {
        this.holder = holder;
        this.sink = sinks.isResolvable() ? sinks.get() : null;
    }

    RulesBootLoader(RuleSetHolder holder, RouteConfigSink directSink) {
        this.holder = holder;
        this.sink = directSink;
    }

    void onStartup(@Observes StartupEvent ev) {
        if (sink != null && sink.durable()) {
            var latest = sink.loadLatest();
            if (latest.isPresent()) {
                List<String> errors = holder.applyCandidate(latest.get());
                if (errors.isEmpty()) {
                    LOG.info("[rules-boot] restored applied rule set v{} from durable SoT",
                            holder.version());
                    return;
                }
                LOG.warn("[rules-boot] durable payload rejected ({}), falling back to seed",
                        errors.size());
            }
        }
        for (Path candidate : List.of(Path.of("configs/dra-rules.json"),
                Path.of("../configs/dra-rules.json"))) {
            if (Files.exists(candidate)) {
                try {
                    List<String> errors = holder.applyCandidate(Files.readString(candidate));
                    LOG.info("[rules-boot] seeded rule set from {} (errors={})",
                            candidate, errors.size());
                } catch (Exception e) {
                    LOG.warn("[rules-boot] seed {} failed: {}", candidate, e.toString());
                }
                return;
            }
        }
        LOG.info("[rules-boot] no durable rule set and no seed file — engine starts empty");
    }
}
