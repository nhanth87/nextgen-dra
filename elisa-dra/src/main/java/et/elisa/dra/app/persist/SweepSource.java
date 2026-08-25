package et.elisa.dra.app.persist;

import et.elisa.dra.core.bind.BindingEntry;

import java.time.Instant;
import java.util.List;

public interface SweepSource {

    List<BindingEntry> sweepExpired(Instant now, int limit);
}
