package et.elisa.dra.core.bind;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgBindingDaoH2Test {

    private static PgBindingDao dao;
    private static Instant base;

    @BeforeAll
    static void init() throws SQLException {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:drabindingdao;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE dra_binding (
                        "key"           VARCHAR(255) PRIMARY KEY,
                        group_id        VARCHAR(255) NOT NULL,
                        peer_id         VARCHAR(255) NOT NULL,
                        origin_host     VARCHAR(255),
                        origin_realm    VARCHAR(255),
                        ingress_peer_id VARCHAR(255),
                        created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at      TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX idx_dra_binding_expires_at ON dra_binding (expires_at)");
        }
        dao = new PgBindingDao(ds);
        base = Instant.now();
    }

    private static BindingEntry entry(String key, Instant expires) {
        return new BindingEntry(key, "hss-pool", "hss-a", "MME-01", "epc.mnc01.mcc452.3gppnetwork.org",
                "mme-01-link", base, expires);
    }

    private static BindingEntry freshEntry(String key) {
        return entry(key, base.plus(Duration.ofHours(24)));
    }

    @Test
    void upsertInsertsThenConflictsUpdateSameKey() {
        dao.removeBatch(List.of("IMSI:h2-1"));
        dao.upsertBatch(List.of(freshEntry("IMSI:h2-1")));
        assertEquals(List.of("IMSI:h2-1"), dao.loadNotExpired(base).stream()
                .map(BindingEntry::key).filter(k -> k.equals("IMSI:h2-1")).toList());
        BindingEntry moved = new BindingEntry("IMSI:h2-1", "hss-pool", "hss-b", "MME-02", "realm",
                "mme-02-link", base, freshEntry("IMSI:h2-1").expiresAt());
        dao.upsertBatch(List.of(moved));
        List<BindingEntry> rows = dao.loadNotExpired(base).stream()
                .filter(e -> e.key().equals("IMSI:h2-1")).toList();
        assertEquals(1, rows.size(), "upsert must not duplicate key");
        assertEquals("hss-b", rows.getFirst().peerId());
        assertEquals("mme-02-link", rows.getFirst().ingressPeerId());
    }

    @Test
    void loadNotExpiredFiltersExpiredRows() {
        dao.upsertBatch(List.of(freshEntry("IMSI:h2-live"),
                entry("IMSI:h2-dead", base.minusSeconds(10))));
        List<String> keys = dao.loadNotExpired(base).stream().map(BindingEntry::key).toList();
        assertTrue(keys.contains("IMSI:h2-live"));
        assertTrue(!keys.contains("IMSI:h2-dead"));
    }

    @Test
    void removeBatchDeletesOnlyListedKeys() {
        dao.upsertBatch(List.of(freshEntry("IMSI:h2-r1"), freshEntry("IMSI:h2-r2"),
                freshEntry("IMSI:h2-keep")));
        dao.removeBatch(List.of("IMSI:h2-r1", "IMSI:h2-r2"));
        List<String> keys = dao.loadNotExpired(base).stream().map(BindingEntry::key).toList();
        assertTrue(!keys.contains("IMSI:h2-r1"));
        assertTrue(!keys.contains("IMSI:h2-r2"));
        assertTrue(keys.contains("IMSI:h2-keep"));
    }

    @Test
    void purgeExpiredDeletesStaleRows() {
        dao.upsertBatch(List.of(entry("IMSI:h2-stale", base.minusSeconds(5)),
                freshEntry("IMSI:h2-fresh")));
        int purged = dao.purgeExpired(base);
        assertTrue(purged >= 1);
        List<String> keys = dao.loadNotExpired(base).stream().map(BindingEntry::key).toList();
        assertTrue(!keys.contains("IMSI:h2-stale"));
        assertTrue(keys.contains("IMSI:h2-fresh"));
    }

    @Test
    void roundTripPreservesAllContractFields() {
        dao.removeBatch(List.of("MSISDN:h2-full"));
        BindingEntry original = new BindingEntry("MSISDN:h2-full", "g1", "peerX",
                "host.example", "realm.example", "link-9", base.minusSeconds(30),
                base.plus(Duration.ofHours(48)));
        dao.upsertBatch(List.of(original));
        BindingEntry loaded = dao.loadNotExpired(base).stream()
                .filter(e -> e.key().equals("MSISDN:h2-full")).findFirst().orElseThrow();
        assertEquals(truncMicros(original), truncMicros(loaded),
                "TIMESTAMP columns carry microsecond precision, nanos truncated");
        assertEquals(original.groupId(), loaded.groupId());
        assertEquals(original.peerId(), loaded.peerId());
        assertEquals(originAndIngress(original), originAndIngress(loaded));
        assertEquals("MSISDN:h2-full", loaded.key());
    }

    private static String originAndIngress(BindingEntry e) {
        return e.originHost() + "|" + e.originRealm() + "|" + e.ingressPeerId();
    }

    private static BindingEntry truncMicros(BindingEntry e) {
        return new BindingEntry(e.key(), e.groupId(), e.peerId(), e.originHost(),
                e.originRealm(), e.ingressPeerId(), trunc(e.createdAt()), trunc(e.expiresAt()));
    }

    private static Instant trunc(Instant t) {
        return Instant.ofEpochSecond(t.getEpochSecond(), (t.getNano() + 500) / 1000 * 1000);
    }
}
