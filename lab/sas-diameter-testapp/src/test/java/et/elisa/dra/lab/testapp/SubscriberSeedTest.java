package et.elisa.dra.lab.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SubscriberSeedTest {

    @Test
    void labDefaultsContainTheFiveAttackProfiles() {
        List<SubscriberSeeds.Spec> seeds = SubscriberSeeds.labDefaults();
        assertEquals(5, seeds.size());
        assertEquals("4520402000000001", seeds.get(0).imsi());
        assertEquals(Boolean.TRUE, seeds.get(0).attached());
        assertEquals(Boolean.FALSE, seeds.get(0).barred());

        assertEquals(Boolean.TRUE, seeds.get(1).barred());
        assertEquals(Boolean.FALSE, seeds.get(2).attached());
        assertEquals(0, seeds.get(3).authVectorsAvailable());
        assertTrue(seeds.get(4).imsi().startsWith("452040999"));
    }

    @Test
    void jsonlParseAndApply() {
        String content = """
                # comment line
                {"imsi":"452041110000001","msisdn":"+251700000001","attached":true}

                {"imsi":"452041110000002","barred":true,"authVectorsAvailable":3,"subscribedRat":"nr"}
                """;
        List<SubscriberSeeds.Spec> specs = SubscriberSeeds.parse(content);
        assertEquals(2, specs.size());

        HssSimulator hss = new HssSimulator(new MessageLog());
        SubscriberSeeds.apply(hss, specs);

        SubscriberState first = hss.find("452041110000001").orElseThrow();
        assertEquals("+251700000001", first.msisdn());
        assertTrue(first.attached());

        SubscriberState second = hss.find("452041110000002").orElseThrow();
        assertTrue(second.barred());
        assertEquals(3, second.authVectorsAvailable());
        assertEquals("NR", second.subscribedRat());
    }

    @Test
    void parseRejectsEmptyAndImsilessContent() {
        assertThrows(IllegalArgumentException.class,
                () -> SubscriberSeeds.parse("# only comments\n\n"));
        assertThrows(IllegalArgumentException.class,
                () -> SubscriberSeeds.parse("{\"msisdn\":\"+251700000001\"}"));
    }

    @Test
    void upsertCreateOrUpdatePreservesMutableState() {
        HssSimulator hss = new HssSimulator(new MessageLog());
        hss.upsert("452000000000001", "+251100000001").setBarred(true);

        SubscriberState updated = hss.upsert("452000000000001", "+251100000002");
        assertTrue(updated.barred());
        assertEquals("+251100000002", updated.msisdn());
        assertTrue(hss.find("+251100000002").isPresent());
        assertTrue(hss.find("+251100000001").isEmpty());
    }

    @Test
    void defaultMsisdnIsDeterministic() {
        assertEquals("+251900000009", HssSimulator.defaultMsisdn("452099900000009"));
        assertEquals("+12345", HssSimulator.defaultMsisdn("12345"));
    }

    @Test
    void demoSubscriberAndBindingStillSeeded() {
        HssSimulator hss = new HssSimulator(new MessageLog());
        assertTrue(hss.find(HssSimulator.DEMO_IMSI).isPresent());
        assertTrue(hss.find(HssSimulator.DEMO_MSISDN).isPresent());
        assertEquals(BindingRegistry.DEMO_IP + "/" + HssSimulator.DEMO_MSISDN,
                hss.bindings().find(BindingRegistry.DEMO_IP).ip() + "/"
                        + hss.bindings().find(BindingRegistry.DEMO_IP).msisdn());
    }
}
