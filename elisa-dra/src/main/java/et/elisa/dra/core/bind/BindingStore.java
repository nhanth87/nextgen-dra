package et.elisa.dra.core.bind;

import java.util.Optional;

public interface BindingStore {

    Optional<BindingEntry> get(String key);

    void put(BindingEntry entry);

    boolean remove(String key);

    long size();
}
