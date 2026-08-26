package et.elisa.dra.app.persist;

import java.util.Optional;

/** Persistence seam for applied routing rule sets (versioned, durable). */
public interface RouteConfigSink {

    /** Durable upsert of an applied candidate; best-effort, must not throw. */
    void persistApplied(int version, String json);

    /** Latest durable payload if any (boot restore). */
    Optional<String> loadLatest();

    /** Whether the sink is wired (false in unit-test/memory-only setups). */
    default boolean durable() {
        return true;
    }
}
