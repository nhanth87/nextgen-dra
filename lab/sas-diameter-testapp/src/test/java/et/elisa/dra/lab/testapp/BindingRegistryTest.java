package et.elisa.dra.lab.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class BindingRegistryTest {

    @Test
    void demoBindingSeededLikeOriginal() {
        BindingRegistry registry = new BindingRegistry();
        BindingRegistry.Binding binding = registry.find(BindingRegistry.DEMO_IP);
        assertEquals(HssSimulator.DEMO_MSISDN, binding.msisdn());
        assertEquals(HssSimulator.DEMO_IMSI, binding.imsi());
    }

    @Test
    void upsertFindRemoveLifecycle() {
        BindingRegistry registry = new BindingRegistry();
        registry.upsert("10.0.0.1", "+251700000001", "452041110000001");
        registry.upsert("10.0.0.2", "+251700000002", null);

        assertEquals("452041110000001", registry.find("10.0.0.1").imsi());
        assertNull(registry.find("10.0.0.2").imsi());
        assertNull(registry.find(null));
        assertNull(registry.find("10.9.9.9"));

        BindingRegistry.Binding replaced = registry.upsert("10.0.0.1", "+251700000003", null);
        assertEquals("+251700000003", replaced.msisdn());
        assertEquals(3, registry.size());

        assertEquals("+251700000003", registry.remove("10.0.0.1").msisdn());
        assertNull(registry.remove("10.0.0.1"));
        assertEquals(2, registry.size());
    }

    @Test
    void listIsOrderedByIpAndClearEmpties() {
        BindingRegistry registry = new BindingRegistry();
        registry.upsert("192.168.0.2", "+251700000002", null);
        registry.upsert("192.168.0.10", "+251700000010", null);
        List<BindingRegistry.Binding> ordered = List.copyOf(registry.list());
        assertEquals(List.of(BindingRegistry.DEMO_IP, "192.168.0.10", "192.168.0.2"),
                ordered.stream().map(BindingRegistry.Binding::ip).toList());

        registry.clear();
        assertTrue(registry.list().isEmpty());
        assertEquals(0, registry.size());
    }
}
