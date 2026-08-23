package et.elisa.dra.app.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySchemaAlignmentTest {

    private static String v1Sql() throws IOException {
        try (InputStream in = EntitySchemaAlignmentTest.class
                .getResourceAsStream("/db/migration/V1__dra_baseline.sql")) {
            assertNotNull(in, "V1__dra_baseline.sql must exist on classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> v1Columns(String table) throws IOException {
        Matcher m = Pattern.compile("CREATE TABLE " + table + "\\s*\\((.*?)\\);",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(v1Sql());
        assertTrue(m.find(), "CREATE TABLE " + table + " missing in V1");
        Set<String> cols = new LinkedHashSet<>();
        for (String line : m.group(1).split(",")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.toUpperCase().startsWith("CREATE INDEX")) {
                continue;
            }
            cols.add(trimmed.split("\\s+")[0].replace("\"", "").toLowerCase());
        }
        return cols;
    }

    private static String columnName(Field f) {
        Column col = f.getAnnotation(Column.class);
        if (col != null && !col.name().isBlank()) {
            return col.name().replace("\"", "").toLowerCase();
        }
        return f.getName().toLowerCase();
    }

    private static void assertEntityMatchesTable(Class<?> entity, String table) throws IOException {
        Table t = entity.getAnnotation(Table.class);
        assertNotNull(t, entity.getSimpleName() + " missing @Table");
        assertEquals(table, t.name(), entity.getSimpleName() + " must map table " + table);
        Set<String> entityCols = new LinkedHashSet<>();
        for (Field f : entity.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            entityCols.add(columnName(f));
        }
        Set<String> sqlCols = v1Columns(table);
        assertEquals(sqlCols, entityCols,
                entity.getSimpleName() + " columns must exactly match V1 table " + table);
    }

    @Test
    void draBindingEntityMatchesV1() throws IOException {
        assertEntityMatchesTable(DraBindingEntity.class, "dra_binding");
    }

    @Test
    void auditLogEntityMatchesV1() throws IOException {
        assertEntityMatchesTable(AuditLogEntity.class, "audit_log");
    }

    @Test
    void routeConfigEntityMatchesV1() throws IOException {
        assertEntityMatchesTable(RouteConfigEntity.class, "route_config");
    }

    @Test
    void bindingKeyColumnIsQuotedReservedWordSafe() throws Exception {
        Field f = DraBindingEntity.class.getDeclaredField("bindingKey");
        Column col = f.getAnnotation(Column.class);
        assertNotNull(col, "bindingKey needs explicit @Column mapping to V1 key column");
        assertEquals("\"key\"", col.name(),
                "key is reserved in H2; quoted identifier keeps PG lowercase semantics");
    }
}
