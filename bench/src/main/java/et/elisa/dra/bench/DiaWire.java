package et.elisa.dra.bench;

import et.elisa.dra.core.wire.DiaAvp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DiaWire {

    public static final int FLAG_REQUEST = 0x80;
    public static final int FLAG_PROXYABLE = 0x40;
    private static final int HEADER_LEN = 20;

    private DiaWire() {
    }

    public record Header(int version, int length, int flags, int commandCode,
                         int applicationId, long hopByHopId, long endToEndId) {

        public boolean isRequest() {
            return (flags & FLAG_REQUEST) != 0;
        }
    }

    public static DiaAvp utf8(int code, int vendorId, boolean mandatory, String value) {
        return new DiaAvp(code, vendorId, mandatory, DiaAvp.TYPE_UTF8,
                value, value.getBytes(StandardCharsets.UTF_8), null);
    }

    public static DiaAvp u32(int code, int vendorId, boolean mandatory, long value) {
        return new DiaAvp(code, vendorId, mandatory, DiaAvp.TYPE_UINT32,
                value, new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value}, null);
    }

    public static byte[] encode(int flags, int commandCode, int applicationId,
                                long hopByHopId, long endToEndId, List<DiaAvp> avps) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<byte[]> encoded = new ArrayList<>(avps.size());
        int total = HEADER_LEN;
        for (DiaAvp avp : avps) {
            byte[] b = encodeAvp(avp);
            encoded.add(b);
            total += b.length;
        }
        out.write(0x01);
        out.write((total >>> 16) & 0xFF);
        out.write((total >>> 8) & 0xFF);
        out.write(total & 0xFF);
        out.write(flags & 0xFF);
        out.write((commandCode >>> 16) & 0xFF);
        out.write((commandCode >>> 8) & 0xFF);
        out.write(commandCode & 0xFF);
        writeInt(out, applicationId);
        writeInt(out, (int) hopByHopId);
        writeInt(out, (int) endToEndId);
        for (byte[] b : encoded) {
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    public static Header decodeHeader(byte[] frame) {
        return new Header(frame[0] & 0xFF,
                ((frame[1] & 0xFF) << 16) | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF),
                frame[4] & 0xFF,
                ((frame[5] & 0xFF) << 16) | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF),
                readInt(frame, 8),
                readInt(frame, 12) & 0xFFFFFFFFL,
                readInt(frame, 16) & 0xFFFFFFFFL);
    }

    public static List<DiaAvp> decodeAvps(byte[] frame) {
        List<DiaAvp> avps = new ArrayList<>();
        int pos = HEADER_LEN;
        while (pos + 12 <= frame.length) {
            int code = readInt(frame, pos);
            int flags = frame[pos + 4] & 0xFF;
            int len = ((frame[pos + 5] & 0xFF) << 16)
                    | ((frame[pos + 6] & 0xFF) << 8) | (frame[pos + 7] & 0xFF);
            boolean vendorFlag = (flags & 0x80) != 0;
            boolean mandatoryFlag = (flags & 0x40) != 0;
            int hdr = vendorFlag ? 12 : 8;
            int dataLen = Math.max(0, len - hdr);
            int vendorId = vendorFlag ? readInt(frame, pos + 8) : 0;
            byte[] data = new byte[dataLen];
            System.arraycopy(frame, pos + hdr, data, 0, Math.min(dataLen, frame.length - pos - hdr));
            avps.add(DiaAvp.raw(code, vendorId, mandatoryFlag, data));
            pos += hdr + pad4(dataLen);
        }
        return avps;
    }

    public static long resultCodeOf(byte[] frame) {
        for (DiaAvp avp : decodeAvps(frame)) {
            if (avp.code() == 268 && avp.rawBytes() != null && avp.rawBytes().length == 4) {
                return readInt(avp.rawBytes(), 0) & 0xFFFFFFFFL;
            }
        }
        return 0;
    }

    private static byte[] encodeAvp(DiaAvp avp) {
        byte[] data = avp.rawBytes() != null ? avp.rawBytes()
                : avp.value() instanceof String s ? s.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        boolean vendor = avp.vendorId() != 0;
        int flags = (avp.mandatory() ? 0x40 : 0) | (vendor ? 0x80 : 0);
        int len = (vendor ? 12 : 8) + data.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(len + pad4(data.length));
        writeInt(out, avp.code());
        out.write(flags);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        if (vendor) {
            writeInt(out, avp.vendorId());
        }
        out.writeBytes(data);
        for (int i = 0; i < pad4(data.length) - data.length; i++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int pad4(int n) {
        return (n + 3) & ~3;
    }
}
