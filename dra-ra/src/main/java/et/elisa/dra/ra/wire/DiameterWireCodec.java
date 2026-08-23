package et.elisa.dra.ra.wire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class DiameterWireCodec {

    public static final int HEADER_LENGTH = 20;
    public static final int AVP_HEADER_LENGTH = 8;
    private static final int FLAG_V = 0x80;
    private static final int FLAG_M = 0x40;
    private static final int FLAG_P = 0x20;

    public record Header(int version, int length, int flags, int commandCode,
                         long applicationId, long hopByHopId, long endToEndId) {
    }

    private DiameterWireCodec() {
    }

    public static byte[] encode(DiaMsg msg) {
        ByteBuf buf = Unpooled.buffer(estimateSize(msg));
        encode(msg, buf);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    public static void encode(DiaMsg msg, ByteBuf out) {
        List<DiaAvp> all = effectiveAvps(msg);
        int bodyLength = 0;
        List<ByteBuf> encoded = new ArrayList<>(all.size());
        for (DiaAvp avp : all) {
            ByteBuf b = encodeAvp(avp);
            encoded.add(b);
            bodyLength += b.readableBytes();
        }
        int total = HEADER_LENGTH + bodyLength;
        out.writeByte(msg.version());
        out.writeMedium(total);
        out.writeByte(msg.flags());
        out.writeMedium(msg.commandCode());
        out.writeInt((int) msg.applicationId());
        out.writeInt((int) msg.hopByHopId());
        out.writeInt((int) msg.endToEndId());
        encoded.forEach(out::writeBytes);
        encoded.forEach(ByteBuf::release);
    }

    public static DiaMsg decode(byte[] bytes) {
        return decode(Unpooled.wrappedBuffer(bytes));
    }

    public static DiaMsg decode(ByteBuf in) {
        if (in.readableBytes() < HEADER_LENGTH) {
            throw new IllegalArgumentException("truncated diameter header: " + in.readableBytes() + " bytes");
        }
        int version = in.readUnsignedByte();
        if (version != 1) {
            throw new IllegalArgumentException("unsupported diameter version " + version);
        }
        int length = in.readUnsignedMedium();
        if (length < HEADER_LENGTH || length - 4 > in.readableBytes()) {
            throw new IllegalArgumentException("bad diameter length " + length);
        }
        int flags = in.readUnsignedByte();
        int commandCode = in.readUnsignedMedium();
        int applicationId = in.readInt();
        long hbh = in.readUnsignedInt();
        long e2e = in.readUnsignedInt();

        List<DiaAvp> avps = new ArrayList<>();
        String sessionId = "";
        String originHost = "";
        String originRealm = "";
        String destinationHost = "";
        String destinationRealm = "";
        int resultCode = 0;
        int offset = HEADER_LENGTH;
        while (offset + AVP_HEADER_LENGTH <= length) {
            int code = in.readInt();
            int avpFlags = in.readUnsignedByte();
            int avpLength = in.readUnsignedMedium();
            if (avpLength < AVP_HEADER_LENGTH || offset + avpLength > length) {
                throw new IllegalArgumentException("bad avp length " + avpLength + " for code " + code);
            }
            long vendorId = (avpFlags & FLAG_V) != 0 ? in.readUnsignedInt() : 0L;
            boolean mandatory = (avpFlags & FLAG_M) != 0;
            int dataLength = avpLength - AVP_HEADER_LENGTH - ((avpFlags & FLAG_V) != 0 ? 4 : 0);
            byte[] data = new byte[Math.max(0, dataLength)];
            in.readBytes(data);
            int padding = pad(avpLength) - avpLength;
            if (padding > 0 && offset + avpLength + padding <= length) {
                in.skipBytes(padding);
            }
            avps.add(DiaAvp.raw(code, (int) vendorId, mandatory, data));
            switch (code) {
                case AvpCodes.SESSION_ID -> sessionId = utf8(data);
                case AvpCodes.ORIGIN_HOST -> originHost = utf8(data);
                case AvpCodes.ORIGIN_REALM -> originRealm = utf8(data);
                case AvpCodes.DESTINATION_HOST -> destinationHost = utf8(data);
                case AvpCodes.DESTINATION_REALM -> destinationRealm = utf8(data);
                case AvpCodes.RESULT_CODE -> resultCode = uint32(data);
                default -> { }
            }
            offset += pad(avpLength);
        }
        return new DiaMsg(version, flags, commandCode, applicationId, hbh, e2e,
                sessionId, originHost, originRealm, destinationHost, destinationRealm,
                resultCode, List.copyOf(avps));
    }

    public static Header peekHeader(ByteBuf frame) {
        if (frame.readableBytes() < HEADER_LENGTH) {
            throw new IllegalArgumentException("truncated diameter header: " + frame.readableBytes() + " bytes");
        }
        int saved = frame.readerIndex();
        try {
            int version = frame.readUnsignedByte();
            int length = frame.readUnsignedMedium();
            int flags = frame.readUnsignedByte();
            int commandCode = frame.readUnsignedMedium();
            long appId = frame.readUnsignedInt();
            long hbh = frame.readUnsignedInt();
            long e2e = frame.readUnsignedInt();
            return new Header(version, length, flags, commandCode, appId, hbh, e2e);
        } finally {
            frame.readerIndex(saved);
        }
    }

    private static List<DiaAvp> effectiveAvps(DiaMsg msg) {
        Set<Integer> present = new HashSet<>();
        msg.avps().forEach(a -> present.add(a.code()));
        List<DiaAvp> out = new ArrayList<>(msg.avps());
        addIfAbsent(out, present, utf8OrNull(AvpCodes.SESSION_ID, msg.sessionId()));
        addIfAbsent(out, present, utf8OrNull(AvpCodes.ORIGIN_HOST, msg.originHost()));
        addIfAbsent(out, present, utf8OrNull(AvpCodes.ORIGIN_REALM, msg.originRealm()));
        if (msg.isRequest()) {
            addIfAbsent(out, present, utf8OrNull(AvpCodes.DESTINATION_HOST, msg.destinationHost()));
            addIfAbsent(out, present, utf8OrNull(AvpCodes.DESTINATION_REALM, msg.destinationRealm()));
        } else if (msg.resultCode() > 0) {
            addIfAbsent(out, present, DiaAvp.uint32(AvpCodes.RESULT_CODE, msg.resultCode()));
        }
        return out;
    }

    private static void addIfAbsent(List<DiaAvp> out, Set<Integer> present, DiaAvp avp) {
        if (avp != null && !present.contains(avp.code())) {
            out.add(avp);
            present.add(avp.code());
        }
    }

    private static DiaAvp utf8OrNull(int code, String value) {
        return value == null || value.isBlank() ? null : DiaAvp.utf8(code, value);
    }

    private static ByteBuf encodeAvp(DiaAvp avp) {
        byte[] data = avpData(avp);
        boolean vendor = avp.vendorId() != 0;
        int length = AVP_HEADER_LENGTH + (vendor ? 4 : 0) + data.length;
        ByteBuf out = Unpooled.buffer(pad(length));
        out.writeInt(avp.code());
        int flags = (vendor ? FLAG_V : 0) | (avp.mandatory() ? FLAG_M : 0) | FLAG_P;
        out.writeByte(flags);
        out.writeMedium(length);
        if (vendor) {
            out.writeInt(avp.vendorId());
        }
        out.writeBytes(data);
        out.writeZero(pad(length) - length);
        return out;
    }

    private static byte[] avpData(DiaAvp avp) {
        if (avp.rawBytes() != null) {
            return avp.rawBytes();
        }
        Object v = avp.value();
        return switch (avp.typeIndex()) {
            case DiaAvp.TYPE_UTF8 -> v == null ? new byte[0] : v.toString().getBytes(StandardCharsets.UTF_8);
            case DiaAvp.TYPE_UINT32, DiaAvp.TYPE_INT32, DiaAvp.TYPE_TIME ->
                    u32(v == null ? 0L : ((Number) v).longValue());
            case DiaAvp.TYPE_UINT64 -> u64(v == null ? 0L : ((Number) v).longValue());
            case DiaAvp.TYPE_OCTETS, DiaAvp.TYPE_ADDRESS ->
                    v instanceof byte[] b ? b : new byte[0];
            case DiaAvp.TYPE_GROUPED -> groupedData(avp.children());
            default -> throw new IllegalArgumentException(
                    "cannot encode avp " + avp.code() + " type " + avp.typeIndex());
        };
    }

    private static byte[] groupedData(List<DiaAvp> children) {
        if (children == null || children.isEmpty()) {
            return new byte[0];
        }
        int size = 0;
        List<ByteBuf> parts = new ArrayList<>(children.size());
        for (DiaAvp child : children) {
            ByteBuf b = encodeAvp(child);
            parts.add(b);
            size += b.readableBytes();
        }
        byte[] out = new byte[size];
        int pos = 0;
        for (ByteBuf b : parts) {
            b.readBytes(out, pos, b.readableBytes());
            pos += b.readableBytes();
            b.release();
        }
        return out;
    }

    private static int estimateSize(DiaMsg msg) {
        return HEADER_LENGTH + 64 + msg.avps().size() * 16;
    }

    private static int pad(int length) {
        int rem = length & 0x3;
        return rem == 0 ? length : length + (4 - rem);
    }

    private static String utf8(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    private static int uint32(byte[] data) {
        if (data.length < 4) {
            return 0;
        }
        return ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
    }

    private static byte[] u32(long value) {
        return new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }

    private static byte[] u64(long value) {
        return new byte[]{
                (byte) (value >>> 56), (byte) (value >>> 48), (byte) (value >>> 40),
                (byte) (value >>> 32), (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }
}
