package et.elisa.dra.core.th;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PseudoHostMapperTest {

    private final ThConfig config = new ThConfig(
            "epc.mnc01.mcc452.3gppnetwork.org", "dra-edge", 4, false,
            Set.of("ipx-edge"));
    private final PseudoHostMapper mapper = new PseudoHostMapper(config);

    @Test
    void deterministicSameKeySamePseudo() {
        String first = mapper.pseudoFor("4520402123456789");
        for (int i = 0; i < 10_000; i++) {
            assertEquals(first, mapper.pseudoFor("4520402123456789"));
        }
    }

    @Test
    void distributionAcrossPseudoHosts() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(mapper.pseudoFor("452040212345000" + (i % 10) + "" + i));
        }
        assertTrue(seen.size() >= 2, "expected spread across pseudos, got " + seen);
    }

    @Test
    void pseudoRecognisedNonPseudoRejected() {
        String pseudo = mapper.pseudoFor("4520402123456789");
        assertTrue(mapper.realFor(pseudo).isPresent());
        assertTrue(mapper.realFor("mme-01.epc.mnc01.mcc452.3gppnetwork.org").isEmpty());
        assertTrue(mapper.realFor(null).isEmpty());
    }

    @Test
    void internalHostDetected() {
        assertTrue(mapper.isInternalHost("hss-a.epc.mnc01.mcc452.3gppnetwork.org"));
        assertTrue(mapper.isInternalHost("mme-01.epc.mnc01.mcc452.3gppnetwork.org"));
        assertEquals(false, mapper.isInternalHost("other.example.org"));
        assertEquals(false, mapper.isInternalHost(null));
    }
}
