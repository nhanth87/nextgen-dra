package et.elisa.dra.app.persist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationSqlSanityTest {

    private static String loadBaseline() throws IOException {
        try (InputStream in = MigrationSqlSanityTest.class
                .getResourceAsStream("/db/migration/V1__dra_baseline.sql")) {
            assertNotNull(in, "V1__dra_baseline.sql must exist on classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String tableBlock(String sql, String table) {
        Matcher m = Pattern.compile("CREATE TABLE " + table + "\\s*\\((.*?)\\);",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(m.find(), "CREATE TABLE " + table + " missing");
        return m.group(1).replaceAll("\\s+", " ");
    }

    @Test
    void baselineContainsAllThreeTables() throws IOException {
        String sql = loadBaseline();
        assertTrue(Pattern.compile("CREATE TABLE dra_binding\\s*\\(", Pattern.CASE_INSENSITIVE)
                .matcher(sql).find());
        assertTrue(Pattern.compile("CREATE TABLE route_config\\s*\\(", Pattern.CASE_INSENSITIVE)
                .matcher(sql).find());
        assertTrue(Pattern.compile("CREATE TABLE audit_log\\s*\\(", Pattern.CASE_INSENSITIVE)
                .matcher(sql).find());
    }

    @Test
    void draBindingColumnsMatchContract() throws IOException {
        String block = tableBlock(loadBaseline(), "dra_binding");
        assertTrue(block.toUpperCase().contains("KEY TEXT PRIMARY KEY"));
        assertTrue(block.toUpperCase().contains("GROUP_ID TEXT NOT NULL"));
        assertTrue(block.toUpperCase().contains("PEER_ID TEXT NOT NULL"));
        assertTrue(block.toUpperCase().contains("INGRESS_PEER_ID"));
        assertTrue(block.toUpperCase().contains("CREATED_AT TIMESTAMPTZ"));
        assertTrue(block.toUpperCase().contains("EXPIRES_AT TIMESTAMPTZ NOT NULL"));
    }

    @Test
    void routeConfigAndAuditUseJsonbAndBigserial() throws IOException {
        String sql = loadBaseline();
        String route = tableBlock(sql, "route_config");
        assertTrue(route.toUpperCase().contains("ID BIGSERIAL PRIMARY KEY"));
        assertTrue(route.toUpperCase().contains("VERSION INT NOT NULL UNIQUE"));
        assertTrue(route.toUpperCase().contains("PAYLOAD JSONB NOT NULL"));
        String audit = tableBlock(sql, "audit_log");
        assertTrue(audit.toUpperCase().contains("ID BIGSERIAL PRIMARY KEY"));
        assertTrue(audit.toUpperCase().contains("DIFF_JSON JSONB"));
        assertTrue(audit.toUpperCase().contains("ACTOR TEXT NOT NULL"));
    }

    @Test
    void balancedParenthesesAndStatementTerminators() throws IOException {
        String sql = loadBaseline();
        long open = sql.chars().filter(c -> c == '(').count();
        long close = sql.chars().filter(c -> c == ')').count();
        assertEquals(open, close, "unbalanced parentheses in migration SQL");
        long statements = Pattern.compile(";").splitAsStream(sql)
                .filter(s -> !s.isBlank()).count();
        assertEquals(5, statements, "expected 3 tables + 2 indexes");
        assertFalseSqlIsEmpty(sql);
    }

    private static void assertFalseSqlIsEmpty(String sql) {
        assertTrue(!sql.isBlank());
    }
}
