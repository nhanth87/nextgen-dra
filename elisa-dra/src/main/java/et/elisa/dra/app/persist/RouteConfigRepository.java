package et.elisa.dra.app.persist;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class RouteConfigRepository implements PanacheRepository<RouteConfigEntity> {

    public Optional<RouteConfigEntity> findByVersion(int version) {
        return find("version", version).firstResultOptional();
    }

    public Optional<RouteConfigEntity> findLatest() {
        return find("ORDER BY version DESC").firstResultOptional();
    }
}
