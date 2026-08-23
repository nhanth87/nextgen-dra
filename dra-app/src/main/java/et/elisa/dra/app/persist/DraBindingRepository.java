package et.elisa.dra.app.persist;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class DraBindingRepository implements PanacheRepository<DraBindingEntity> {

    public List<DraBindingEntity> findNotExpired(Instant now) {
        return list("expiresAt >= ?1", now);
    }

    public long deleteExpired(Instant now) {
        return delete("expiresAt < ?1", now);
    }

    public void upsert(DraBindingEntity entity) {
        getEntityManager().merge(entity);
    }
}
