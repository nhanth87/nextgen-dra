package et.elisa.dra.core.bind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;

public final class PgBindingDao implements PersistenceHook {

    private static final String PG_UPSERT_SQL = """
            INSERT INTO dra_binding("key", group_id, peer_id, origin_host, origin_realm,
                                    ingress_peer_id, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ("key") DO UPDATE SET
                group_id = EXCLUDED.group_id,
                peer_id = EXCLUDED.peer_id,
                origin_host = EXCLUDED.origin_host,
                origin_realm = EXCLUDED.origin_realm,
                ingress_peer_id = EXCLUDED.ingress_peer_id,
                created_at = EXCLUDED.created_at,
                expires_at = EXCLUDED.expires_at
            """;

    private static final String H2_UPSERT_SQL = """
            MERGE INTO dra_binding ("key", group_id, peer_id, origin_host, origin_realm,
                                    ingress_peer_id, created_at, expires_at)
            KEY ("key")
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String DELETE_SQL = "DELETE FROM dra_binding WHERE \"key\" = ANY(?)";

    private static final String SELECT_NOT_EXPIRED_SQL = """
            SELECT "key", group_id, peer_id, origin_host, origin_realm, ingress_peer_id,
                   created_at, expires_at
            FROM dra_binding
            WHERE expires_at >= ?
            """;

    private final DataSource dataSource;
    private final String upsertSql;

    public PgBindingDao(DataSource dataSource) {
        this.dataSource = dataSource;
        this.upsertSql = resolveUpsertSql(dataSource);
    }

    private static String resolveUpsertSql(DataSource ds) {
        try (Connection conn = ds.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product != null && product.toLowerCase(Locale.ROOT).contains("h2")) {
                return H2_UPSERT_SQL;
            }
            return PG_UPSERT_SQL;
        } catch (SQLException e) {
            throw new IllegalStateException("dra_binding dialect detection failed", e);
        }
    }

    @Override
    public void upsertBatch(List<BindingEntry> batch) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            for (BindingEntry e : batch) {
                ps.setString(1, e.key());
                ps.setString(2, e.groupId());
                ps.setString(3, e.peerId());
                ps.setString(4, e.originHost());
                ps.setString(5, e.originRealm());
                ps.setString(6, e.ingressPeerId());
                ps.setTimestamp(7, timestamp(e.createdAt()));
                ps.setTimestamp(8, timestamp(e.expiresAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            throw new IllegalStateException("dra_binding upsert batch failed", ex);
        }
    }

    @Override
    public void removeBatch(List<String> keys) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_SQL)) {
            ps.setArray(1, conn.createArrayOf("text", keys.toArray(String[]::new)));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("dra_binding remove batch failed", ex);
        }
    }

    public List<BindingEntry> loadNotExpired(Instant now) {
        List<BindingEntry> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_NOT_EXPIRED_SQL)) {
            ps.setTimestamp(1, timestamp(now));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BindingEntry(
                            rs.getString("key"),
                            rs.getString("group_id"),
                            rs.getString("peer_id"),
                            rs.getString("origin_host"),
                            rs.getString("origin_realm"),
                            rs.getString("ingress_peer_id"),
                            toInstant(rs.getTimestamp("created_at")),
                            toInstant(rs.getTimestamp("expires_at"))));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("dra_binding load failed", ex);
        }
        return out;
    }

    public int purgeExpired(Instant now) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM dra_binding WHERE expires_at < ?")) {
            ps.setTimestamp(1, timestamp(now));
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("dra_binding purge failed", ex);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
