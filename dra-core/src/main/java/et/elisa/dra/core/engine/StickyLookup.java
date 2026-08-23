package et.elisa.dra.core.engine;

import et.elisa.dra.core.bind.BindingEntry;

import java.util.Optional;

@FunctionalInterface
public interface StickyLookup {

    Optional<BindingEntry> get(String key);

    static StickyLookup empty() {
        return key -> Optional.empty();
    }
}
