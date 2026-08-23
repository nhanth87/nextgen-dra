package et.elisa.dra.bench;

import et.elisa.dra.core.wire.DiaAvp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiaWireTest {

    @Test
    void headerRoundTrip() {
        byte[] frame = DiaWire.encode(DiaWire.FLAG_REQUEST | DiaWire.FLAG_PROXYABLE,
                316, 16777251, 0x1A2B3C4DL, 42L, List.of());
        DiaWire.Header h = DiaWire.decodeHeader(frame);
        assertEquals(20, h.length());
        assertEquals(DiaWire.FLAG_REQUEST | DiaWire.FLAG_PROXYABLE, h.flags());
        assertEquals(316, h.commandCode());
        assertEquals(16777251, h.applicationId());
        assertEquals(0x1A2B3C4DL, h.hopByHopId());
        assertEquals(42L, h.endToEndId());
        assertTrue(h.isRequest());
    }

    @Test
    void avpRoundTripWithPadding() {
        byte[] frame = DiaWire.encode(DiaWire.FLAG_REQUEST, 316, 16777251, 7L, 8L,
                List.of(
                        DiaWire.utf8(1, 0, true, "4520402123456789"),
                        DiaWire.u32(268, 0, true, 2001),
                        DiaWire.utf8(701, 10415, false, "981234567")));
        DiaWire.Header h = DiaWire.decodeHeader(frame);
        assertEquals(frame.length, h.length());

        List<DiaAvp> avps = DiaWire.decodeAvps(frame);
        assertEquals(3, avps.size());
        assertEquals(1, avps.get(0).code());
        assertEquals("4520402123456789",
                new String(avps.get(0).rawBytes(), StandardCharsets.UTF_8));
        assertEquals(true, avps.get(0).mandatory());
        assertEquals(268, avps.get(1).code());
        assertEquals(4, avps.get(1).rawBytes().length);
        assertEquals((2001 << 24) >>> 24, avps.get(1).rawBytes()[3] & 0xFF);
        assertEquals(701, avps.get(2).code());
        assertEquals(10415, avps.get(2).vendorId());
        assertEquals("981234567",
                new String(avps.get(2).rawBytes(), StandardCharsets.UTF_8));
        assertEquals(0, frame.length % 4);
    }

    @Test
    void resultCodeExtractedFromAnswer() {
        byte[] frame = DiaWire.encode(0, 316, 16777251, 9L, 10L,
                List.of(DiaWire.u32(268, 0, true, 2001)));
        assertEquals(2001, DiaWire.resultCodeOf(frame));
    }
}
