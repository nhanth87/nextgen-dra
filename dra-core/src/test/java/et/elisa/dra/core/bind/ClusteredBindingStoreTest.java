package et.elisa.dra.core.bind;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusteredBindingStoreTest {

    private static BindingEntry entry(String key) {
        Instant now = Instant.now();
        return new BindingEntry(key, "hss-pool", "hss-a", "mme-01", "realm", "mme-link",
                now, now.plus(Duration.ofHours(24)));
    }

    @Test
    void delegatesReadsToLocal() {
        InMemoryBindingStore local = new InMemoryBindingStore();
        List<BindingEntry> replicated = new ArrayList<>();
        ClusteredBindingStore store = new ClusteredBindingStore(local,
                new ReplicationHook() {
                    @Override
                    public void onPut(BindingEntry e) {
                        replicated.add(e);
                    }

                    @Override
                    public void onRemove(String key) {
                    }
                });
        assertTrue(store.get("IMSI:1").isEmpty());
        store.put(entry("IMSI:1"));
        Optional<BindingEntry> hit = store.get("IMSI:1");
        assertTrue(hit.isPresent());
        assertEquals(1, store.size());
        assertEquals(1, replicated.size());
        assertEquals("IMSI:1", replicated.getFirst().key());
    }

    @Test
    void removeReplicatesOnlyWhenPresent() {
        InMemoryBindingStore local = new InMemoryBindingStore();
        List<String> removals = new ArrayList<>();
        ClusteredBindingStore store = new ClusteredBindingStore(local,
                new ReplicationHook() {
                    @Override
                    public void onPut(BindingEntry e) {
                    }

                    @Override
                    public void onRemove(String key) {
                        removals.add(key);
                    }
                });
        assertFalse(store.remove("IMSI:x"));
        assertEquals(0, removals.size());
        store.put(entry("IMSI:x"));
        assertTrue(store.remove("IMSI:x"));
        assertEquals(List.of("IMSI:x"), removals);
    }

    @Test
    void defaultConstructorUsesNoopReplication() {
        ClusteredBindingStore store = new ClusteredBindingStore(new InMemoryBindingStore());
        store.put(entry("K"));
        assertTrue(store.get("K").isPresent());
    }

    @Test
    void sharedBusKeepsTwoNodesConsistent() {
        InMemoryBindingStore localA = new InMemoryBindingStore();
        InMemoryBindingStore localB = new InMemoryBindingStore();
        ReplicationHook bus = new ReplicationHook() {
            @Override
            public void onPut(BindingEntry e) {
                localA.put(e);
                localB.put(e);
            }

            @Override
            public void onRemove(String key) {
                localA.remove(key);
                localB.remove(key);
            }
        };
        ClusteredBindingStore nodeA = new ClusteredBindingStore(localA, bus);
        ClusteredBindingStore nodeB = new ClusteredBindingStore(localB, bus);

        nodeA.put(entry("IMSI:42"));
        assertTrue(nodeA.get("IMSI:42").isPresent());
        assertTrue(nodeB.get("IMSI:42").isPresent(), "put on A must replicate to B");
        assertEquals(1, nodeA.replicatedPutCount());

        nodeB.remove("IMSI:42");
        assertTrue(nodeA.get("IMSI:42").isEmpty(), "remove on B must replicate to A");
        assertTrue(nodeB.get("IMSI:42").isEmpty());
        assertEquals(1, nodeB.replicatedRemoveCount());
    }

    @Test
    void replicationHookFailureIsFireAndForget() {
        InMemoryBindingStore local = new InMemoryBindingStore();
        ClusteredBindingStore store = new ClusteredBindingStore(local, new ReplicationHook() {
            @Override
            public void onPut(BindingEntry e) {
                throw new IllegalStateException("replication transport down");
            }

            @Override
            public void onRemove(String key) {
                throw new IllegalStateException("replication transport down");
            }
        });
        store.put(entry("IMSI:7"));
        assertTrue(store.get("IMSI:7").isPresent(), "hook failure must not lose the local write");
        assertEquals(1, store.size());
        assertTrue(store.remove("IMSI:7"));
        assertEquals(0, store.size());
        assertEquals(2, store.replicationFailureCount());
    }

    @Test
    void asyncExecutorKeepsPutNonBlockingUntilDispatched() {
        InMemoryBindingStore local = new InMemoryBindingStore();
        List<Runnable> parked = new ArrayList<>();
        List<BindingEntry> replicated = new ArrayList<>();
        ClusteredBindingStore store = new ClusteredBindingStore(local,
                new ReplicationHook() {
                    @Override
                    public void onPut(BindingEntry e) {
                        replicated.add(e);
                    }

                    @Override
                    public void onRemove(String key) {
                    }
                }, parked::add);
        store.put(entry("IMSI:99"));
        assertEquals(1, store.size());
        assertEquals(0, replicated.size(), "replication must not run inline");
        assertEquals(1, parked.size());
        parked.getFirst().run();
        assertEquals(1, replicated.size());
        assertEquals(1, store.replicatedPutCount());
    }
}
