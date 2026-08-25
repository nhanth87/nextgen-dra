package et.elisa.dra.core.engine;

import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Fixtures {

    public static final int S6A_APP = 16777251;

    public static DiaMsg ulr(String imsi, String origHost, String origRealm,
                             String destRealm, List<DiaAvp> extraAvps) {
        var avps = new java.util.ArrayList<DiaAvp>();
        if (imsi != null) {
            avps.add(DiaAvp.utf8(1, imsi));
        }
        if (extraAvps != null) {
            avps.addAll(extraAvps);
        }
        return new DiaMsg(1, DiaMsg.FLAG_REQUEST | DiaMsg.FLAG_PROXYABLE, 316, S6A_APP,
                0x1001L, 0x2002L, "sess-" + System.nanoTime(), origHost, origRealm,
                null, destRealm, 0, List.copyOf(avps));
    }

    public static DiaMsg withFlags(DiaMsg msg, int flags) {
        return new DiaMsg(msg.version(), flags, msg.commandCode(), msg.applicationId(),
                msg.hopByHopId(), msg.endToEndId(), msg.sessionId(), msg.originHost(),
                msg.originRealm(), msg.destinationHost(), msg.destinationRealm(),
                msg.resultCode(), msg.avps());
    }

    public static DiaAvp visitedPlmn(String mccMnc) {
        int mcc1 = mccMnc.charAt(0) - '0';
        int mcc2 = mccMnc.charAt(1) - '0';
        int mcc3 = mccMnc.charAt(2) - '0';
        boolean mnc3digits = mccMnc.length() > 5;
        int mnc1 = mccMnc.charAt(3) - '0';
        int mnc2 = mccMnc.charAt(4) - '0';
        int mnc3 = mnc3digits ? mccMnc.charAt(5) - '0' : 0xF;
        byte b0 = (byte) ((mcc2 << 4) | mcc1);
        byte b1 = (byte) ((mnc3 << 4) | mcc3);
        byte b2 = (byte) ((mnc2 << 4) | mnc1);
        return new DiaAvp(1407, 10415, true, DiaAvp.TYPE_OCTETS, null,
                new byte[]{b0, b1, b2}, null);
    }

    public static DiaAvp msisdnTbcd(String digits) {
        byte[] out = new byte[(digits.length() + 1) / 2];
        for (int i = 0; i < digits.length(); i += 2) {
            int lo = digits.charAt(i) - '0';
            int hi = i + 1 < digits.length() ? digits.charAt(i + 1) - '0' : 0xF;
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return new DiaAvp(701, 10415, false, DiaAvp.TYPE_OCTETS, null, out, null);
    }

    public static DiaAvp framedIpV4(String ip) {
        String[] parts = ip.split("\\.");
        byte[] raw = new byte[6];
        raw[0] = 0;
        raw[1] = 1;
        for (int i = 0; i < 4; i++) {
            raw[i + 2] = (byte) Integer.parseInt(parts[i]);
        }
        return new DiaAvp(8, 0, false, DiaAvp.TYPE_OCTETS, null, raw, null);
    }

    public static DiaAvp subscriptionIdImsi(String imsi) {
        return DiaAvp.grouped(444, List.of(
                new DiaAvp(450, 0, false, DiaAvp.TYPE_UINT32, 1L, null, null),
                DiaAvp.utf8(443, imsi)));
    }

    public static DiaAvp routeRecord(String host) {
        return DiaAvp.utf8(282, host);
    }

    public static DiaAvp drmp(long value) {
        return DiaAvp.uint32(301, value);
    }

    public static String utf8Bytes(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private Fixtures() {
    }
}
