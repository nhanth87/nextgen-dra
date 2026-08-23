package et.elisa.dra.core.bind;

public interface ReplicationHook {

    void onPut(BindingEntry entry);

    void onRemove(String key);

    static ReplicationHook noop() {
        return new ReplicationHook() {
            @Override
            public void onPut(BindingEntry entry) {
            }

            @Override
            public void onRemove(String key) {
            }
        };
    }
}
