package et.elisa.dra.core.engine;

import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyExtractorImplTest {

    private final KeyExtractorImpl x = new KeyExtractorImpl();

    @Test
    void imsiFromUserNameAndHeaderDerivedKeys() {
        DiaMsg m = Fixtures.ulr("452040123456789", "mme-01.epc.lab", "epc.lab",
                "hss.epc.lab", null);
        Map<String, String> keys = x.extract(m);
        assertEquals("452040123456789", keys.get("IMSI"));
        assertEquals("mme-01.epc.lab", keys.get("ORIG_HOST"));
        assertEquals("epc.lab", keys.get("ORIG_REALM"));
        assertEquals("hss.epc.lab", keys.get("DEST_REALM"));
        assertTrue(keys.containsKey("SESSION_ID"));
        assertFalse(keys.containsKey("MSISDN"));
        assertFalse(keys.containsKey("VISITED_PLMN"));
    }

    @Test
    void imsiFallsBackToSubscriptionIdImsiType() {
        DiaMsg m = Fixtures.ulr(null, "mme-1", "epc.lab", "epc.lab",
                List.of(Fixtures.subscriptionIdImsi("452040999999998")));
        assertEquals("452040999999998", x.extract(m).get("IMSI"));
    }

    @Test
    void msisdnFromTbcdOctets701() {
        DiaAvp msisdn = Fixtures.msisdnTbcd("84912000123");
        DiaMsg m = Fixtures.ulr(null, "mme-1", "epc.lab", "epc.lab", List.of(msisdn));
        assertEquals("84912000123", x.extract(m).get("MSISDN"));
    }

    @Test
    void msisdnFromUtf8String() {
        DiaAvp utf8 = new DiaAvp(701, 10415, false, DiaAvp.TYPE_UTF8, "+84912000456",
                null, null);
        DiaMsg m = Fixtures.ulr(null, "mme-1", "epc.lab", "epc.lab", List.of(utf8));
        assertEquals("84912000456", x.extract(m).get("MSISDN"));
    }

    @Test
    void visitedPlmnTwoAndThreeDigitMnc() {
        DiaMsg two = Fixtures.ulr(null, "mme-1", "epc.lab", "epc.lab",
                List.of(Fixtures.visitedPlmn("45201")));
        assertEquals("45201", x.extract(two).get("VISITED_PLMN"));

        DiaMsg three = Fixtures.ulr(null, "mme-1", "epc.lab", "epc.lab",
                List.of(Fixtures.visitedPlmn("452040")));
        assertEquals("452040", x.extract(three).get("VISITED_PLMN"));

        DiaMsg vinaphone = Fixtures.ulr(null, "mme-1", "epc.lab", "epc.lab",
                List.of(Fixtures.visitedPlmn("45204")));
        assertEquals("45204", x.extract(vinaphone).get("VISITED_PLMN"));
    }

    @Test
    void framedIpV4AddressFamilyPrefixed() {
        DiaMsg m = Fixtures.ulr(null, "pgw-1", "epc.lab", "pcrf.epc.lab",
                List.of(Fixtures.framedIpV4("10.77.3.9")));
        assertEquals("10.77.3.9", x.extract(m).get("FRAMED_IP"));
    }

    @Test
    void framedIpV6() {
        byte[] v6 = new byte[18];
        v6[1] = 2;
        v6[2] = 32;
        v6[3] = 1;
        v6[17] = 7;
        DiaAvp avp = new DiaAvp(8, 0, false, DiaAvp.TYPE_OCTETS, null, v6, null);
        Map<String, String> keys = x.extract(Fixtures.ulr(null, "pgw-1", "epc.lab",
                "pcrf.epc.lab", List.of(avp)));
        assertTrue(keys.get("FRAMED_IP").contains("2001:"));
    }

    @Test
    void apnFromCalledStationId() {
        DiaMsg m = Fixtures.ulr(null, "pgw-1", "epc.lab", "pcrf.epc.lab",
                List.of(DiaAvp.utf8(30, "ims.mnc01.mcc452.gprs")));
        assertEquals("ims.mnc01.mcc452.gprs", x.extract(m).get("APN"));
    }

    @Test
    void emptyMessageYieldsOnlyHeaderKeys() {
        Map<String, String> keys = x.extract(Fixtures.ulr(null, null, null, null, null));
        assertNull(keys.get("IMSI"));
        assertNull(keys.get("APN"));
        assertNull(keys.get("FRAMED_IP"));
        assertNull(keys.get("MSISDN"));
        assertNull(keys.get("DEST_REALM"));
    }
}
