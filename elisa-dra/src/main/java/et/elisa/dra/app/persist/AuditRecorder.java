package et.elisa.dra.app.persist;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;

@ApplicationScoped
@RegisterForReflection
public class AuditRecorder {

    private final AuditLogRepository auditLogs;

    @Inject
    public AuditRecorder(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @Transactional
    public void record(String actor, String action, String diffJson) {
        auditLogs.persist(new AuditLogEntity(Instant.now(), actor, action, diffJson));
    }
}
