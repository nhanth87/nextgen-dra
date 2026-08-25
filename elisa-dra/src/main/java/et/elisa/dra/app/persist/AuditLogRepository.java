package et.elisa.dra.app.persist;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AuditLogRepository implements PanacheRepository<AuditLogEntity> {
}
