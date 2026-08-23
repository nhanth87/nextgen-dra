package et.elisa.dra.ra.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import io.netty.buffer.Unpooled;

class DiameterWireCodecTest {

    private DiaMsg relayRequest() {
        return new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, 316,
                16777251, 0xDEADBEEFL, 0xCAFEBABEL, "sess;1;2",
                "mme-01.epc.lab", "epc.mnc01.mcc452.3gppnetwork.org",
                "", "epc.mnc01.mcc452.3gppnetwork.org", 0,
                List.of(
                        DiaAvp.utf8(1, "452040100000001"),
                        DiaAvp.raw(1407, 10415, true, new byte[]{0x52, 0x04, 0x01}),
                        DiaAvp.grouped(444, List.of(DiaAvp.uint32(450, 0), DiaAvp.utf8(443, "452040100000001")))));
    }

    @Test
    void headerRoundtripPreservesIdentityFields() {
        byte[] bytes = DiameterWireCodec.encode(relayRequest());
        assertTrue(bytes.length > DiameterWireCodec.HEADER_LENGTH);
        assertEquals(0, bytes.length % 4);
        var h = DiameterWireCodec.peekHeader(Unpooled.wrappedBuffer(bytes));
        assertEquals(1, h.version());
        assertEquals(bytes.length, h.length());
        assertEquals(DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, h.flags());
        assertEquals(316, h.commandCode());
        assertEquals(16777251L, h.applicationId());
        assertEquals(0xDEADBEEFL, h.hopByHopId());
        assertEquals(0xCAFEBABEL, h.endToEndId());
    }

    @Test
    void decodeRecoversRawAvpsAndComponents() {
        DiaMsg decoded = DiameterWireCodec.decode(DiameterWireCodec.encode(relayRequest()));
        assertTrue(decoded.isRequest());
        assertEquals("sess;1;2", decoded.sessionId());
        assertEquals("mme-01.epc.lab", decoded.originHost());
        assertEquals("epc.mnc01.mcc452.3gppnetwork.org", decoded.destinationRealm());
        assertEquals(0xDEADBEEFL, decoded.hopByHopId());
        assertEquals(0xCAFEBABEL, decoded.endToEndId());

        DiaAvp imsi = find(decoded.avps(), 1);
        assertArrayEquals("452040100000001".getBytes(), imsi.rawBytes());
        DiaAvp plmn = find(decoded.avps(), 1407);
        assertEquals(10415, plmn.vendorId());
        assertTrue(plmn.mandatory());
        assertArrayEquals(new byte[]{0x52, 0x04, 0x01}, plmn.rawBytes());
        DiaAvp sub = find(decoded.avps(), 444);
        assertEquals(DiaAvp.TYPE_OCTETS, sub.typeIndex());
        assertEquals(36, sub.rawBytes().length);
    }

    @Test
    void encodeDecodeEncodeIsByteStable() {
        byte[] first = DiameterWireCodec.encode(relayRequest());
        byte[] second = DiameterWireCodec.encode(DiameterWireCodec.decode(first));
        assertArrayEquals(first, second);
    }

    @Test
    void answerCarriesResultCodeAvpOnlyWhenAnswer() {
        DiaMsg request = relayRequest();
        byte[] reqBytes = DiameterWireCodec.encode(request);
        assertTrue(find(DiameterWireCodec.decode(reqBytes).avps(), AvpCodes.RESULT_CODE) == null);

        DiaMsg answer = request.asAnswer(2001).withHopByHop(0xDEADBEEFL);
        DiaMsg decodedAnswer = DiameterWireCodec.decode(DiameterWireCodec.encode(answer));
        assertFalse(decodedAnswer.isRequest());
        assertEquals(2001, decodedAnswer.resultCode());
        DiaAvp rc = find(decodedAnswer.avps(), AvpCodes.RESULT_CODE);
        assertNotNull(rc);
        assertArrayEquals(new byte[]{0, 0, 0x07, (byte) 0xD1}, rc.rawBytes());
    }

    @Test
    void avpPaddingIsFourByteAligned() {
        DiaMsg msg = new DiaMsg(1, DiaMsg.FLAG_REQUEST, 280, 0, 1L, 1L,
                "", "o", "r", "", "r", 0,
                List.of(DiaAvp.raw(7, 0, false, new byte[]{1, 2, 3})));
        byte[] bytes = DiameterWireCodec.encode(msg);
        assertEquals(0, bytes.length % 4);
        DiaMsg decoded = DiameterWireCodec.decode(bytes);
        assertArrayEquals(new byte[]{1, 2, 3}, find(decoded.avps(), 7).rawBytes());
    }

    @Test
    void truncatedFramesAreRejectedNotSilentlyAccepted() {
        assertThrows(IllegalArgumentException.class,
                () -> DiameterWireCodec.decode(new byte[10]));
        byte[] full = DiameterWireCodec.encode(relayRequest());
        assertThrows(IllegalArgumentException.class,
                () -> DiameterWireCodec.decode(java.util.Arrays.copyOf(full, 18)));
    }

    private DiaAvp find(List<DiaAvp> avps, int code) {
        return avps.stream().filter(a -> a.code() == code).findFirst().orElse(null);
    }
}
