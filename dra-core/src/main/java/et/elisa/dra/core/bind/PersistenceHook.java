package et.elisa.dra.core.bind;

import java.util.List;

public interface PersistenceHook {

    void upsertBatch(List<BindingEntry> batch);

    void removeBatch(List<String> keys);
}
