package et.elisa.dra.core.wire;

import java.util.List;

public record DiaAvp(int code, int vendorId, boolean mandatory,
                     int typeIndex, Object value, byte[] rawBytes,
                     List<DiaAvp> children) {

    public static final int TYPE_UTF8 = 1;
    public static final int TYPE_UINT32 = 2;
    public static final int TYPE_INT32 = 3;
    public static final int TYPE_UINT64 = 4;
    public static final int TYPE_OCTETS = 5;
    public static final int TYPE_GROUPED = 6;
    public static final int TYPE_ADDRESS = 7;
    public static final int TYPE_TIME = 8;

    public static DiaAvp utf8(int code, String v) {
        return new DiaAvp(code, 0, false, TYPE_UTF8, v, null, null);
    }

    public static DiaAvp uint32(int code, long v) {
        return new DiaAvp(code, 0, false, TYPE_UINT32, v, null, null);
    }

    public static DiaAvp grouped(int code, List<DiaAvp> children) {
        return new DiaAvp(code, 0, false, TYPE_GROUPED, null, null, children);
    }

    public static DiaAvp raw(int code, int vendorId, boolean mandatory, byte[] bytes) {
        return new DiaAvp(code, vendorId, mandatory, TYPE_OCTETS, null, bytes, null);
    }
}
