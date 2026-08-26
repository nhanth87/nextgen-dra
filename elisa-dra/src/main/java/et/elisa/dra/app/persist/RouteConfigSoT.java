package et.elisa.dra.app.persist;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * PG/H2-backed SoT for applied route configs (route_config, versioned) plus
 * the boot-time restore path: DB first, JSON seed file only when DB is empty.
 */
@ApplicationScoped
@RegisterForReflection
public class RouteConfigSoT implements RouteConfigSink {

    private final RouteConfigRepository repository;
    private final AuditRecorder audit;

    @Inject
    public RouteConfigSoT(RouteConfigRepository repository, AuditRecorder audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void persistApplied(int version, String json) {
        try {
            repository.findByVersion(version).ifPresentOrElse(entity -> {
                entity.setPayload(json);
            }, () -> repository.persist(new RouteConfigEntity(version, json,
                    java.time.Instant.now())));
            audit.record("admin", "rules.apply", "{\"version\":" + version + "}");
        } catch (RuntimeException e) {
            org.apache.logging.log4j.LogManager.getLogger(RouteConfigSoT.class)
                    .warn("[route-config-sot] persist failed v{}: {}", version, e.toString());
        }
    }

    @Override
    public Optional<String> loadLatest() {
        try {
            return repository.findLatest().map(RouteConfigEntity::getPayload);
        } catch (RuntimeException e) {
            org.apache.logging.log4j.LogManager.getLogger(RouteConfigSoT.class)
                    .warn("[route-config-sot] loadLatest failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Transactional
    public long count() {
        return repository.count();
    }
}
