package et.elisa.dra.core.bind;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryBindingStoreTest {

    private static BindingEntry entry(String key, Instant created, Instant expires) {
        return new BindingEntry(key, "hss-pool", "hss-a", "mme-01", "epc.mnc01.mcc452.3gppnetwork.org",
                "mme-01-link", created, expires);
    }

    private static BindingEntry fresh(String key) {
        Instant now = Instant.now();
        return entry(key, now, now.plus(Duration.ofHours(24)));
    }

    @Test
    void putGetRoundTrip() {
        InMemoryBindingStore store = new InMemoryBindingStore();
        store.put(fresh("IMSI:452040100000001"));
        Optional<BindingEntry> hit = store.get("IMSI:452040100000001");
        assertTrue(hit.isPresent());
        assertEquals("hss-a", hit.orElseThrow().peerId());
        assertEquals(1, store.size());
    }

    @Test
    void missingKeyReturnsEmpty() {
        InMemoryBindingStore store = new InMemoryBindingStore();
        assertTrue(store.get("IMSI:nobody").isEmpty());
    }

    @Test
    void expiredEntryLazilyEvictedOnGet() {
        InMemoryBindingStore store = new InMemoryBindingStore();
        Instant now = Instant.now();
        store.put(entry("IMSI:dead", now.minusSeconds(3600), now.minusSeconds(60)));
        assertEquals(1, store.size());
        assertTrue(store.get("IMSI:dead").isEmpty());
        assertEquals(0, store.size());
        assertEquals(1, store.expiredCount());
    }

    @Test
    void removeReportsPresence() {
        InMemoryBindingStore store = new InMemoryBindingStore();
        store.put(fresh("MSISDN:84123456789"));
        assertTrue(store.remove("MSISDN:84123456789"));
        assertFalse(store.remove("MSISDN:84123456789"));
        assertEquals(0, store.size());
    }

    @Test
    void lruCapEvictsLeastRecentlyUsed() {
        InMemoryBindingStore store = new InMemoryBindingStore(3);
        store.put(fresh("K1"));
        store.put(fresh("K2"));
        store.put(fresh("K3"));
        store.get("K1");
        store.put(fresh("K4"));
        assertEquals(3, store.size());
        assertTrue(store.get("K2").isEmpty(), "K2 was LRU and must be evicted");
        assertTrue(store.get("K1").isPresent(), "K1 was touched and must survive");
        assertTrue(store.get("K4").isPresent());
        assertEquals(1, store.evictedCount());
    }

    @Test
    void putExistingKeyDoesNotGrowSize() {
        InMemoryBindingStore store = new InMemoryBindingStore(3);
        store.put(fresh("K1"));
        store.put(fresh("K1"));
        assertEquals(1, store.size());
        assertEquals(0, store.evictedCount());
    }

    @Test
    void sweepExpiredRemovesBatchOnly() {
        InMemoryBindingStore store = new InMemoryBindingStore();
        Instant now = Instant.now();
        store.put(entry("E1", now.minusSeconds(10), now.minusSeconds(1)));
        store.put(fresh("ALIVE"));
        store.put(entry("E2", now.minusSeconds(10), now.minusSeconds(2)));

        List<BindingEntry> swept = store.sweepExpired(now);
        assertEquals(List.of("E1", "E2"), swept.stream().map(BindingEntry::key).toList());
        assertEquals(1, store.size());
        assertEquals(2, store.expiredCount());
        assertTrue(store.get("ALIVE").isPresent());
    }

    @Test
    void sweepLimitBoundsBatch() {
        InMemoryBindingStore store = new InMemoryBindingStore();
        Instant now = Instant.now();
        store.put(entry("E1", now.minusSeconds(10), now.minusSeconds(1)));
        store.put(entry("E2", now.minusSeconds(10), now.minusSeconds(1)));
        List<BindingEntry> swept = store.sweepExpired(now, 1);
        assertEquals(1, swept.size());
        assertEquals(1, store.size());
    }

    @Test
    void refreshedTtlKeepsEntryAlive() {
        InMemoryBindingStore store = new InMemoryBindingStore();
        Instant now = Instant.now();
        BindingEntry shortTtl = entry("K", now, now.plusSeconds(1));
        store.put(shortTtl);
        store.put(shortTtl.withTtl(now.plus(Duration.ofHours(24))));
        assertTrue(store.get("K").isPresent());
    }

    @Test
    void zeroCapacityRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new InMemoryBindingStore(0));
    }
}
