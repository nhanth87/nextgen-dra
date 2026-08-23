package et.elisa.dra.core.engine;

import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KeyExtractorImpl implements KeyExtractor {

    private static final int SUBSCRIPTION_TYPE_E164 = 0;
    private static final int SUBSCRIPTION_TYPE_IMSI = 1;
    private static final int ADDRESS_FAMILY_IPV4 = 1;
    private static final int ADDRESS_FAMILY_IPV6 = 2;

    @Override
    public Map<String, String> extract(DiaMsg msg) {
        Map<String, String> keys = new LinkedHashMap<>();
        putIfPresent(keys, "SESSION_ID", msg.sessionId());
        putIfPresent(keys, "DEST_HOST", msg.destinationHost());
        putIfPresent(keys, "DEST_REALM", msg.destinationRealm());
        putIfPresent(keys, "ORIG_HOST", msg.originHost());
        putIfPresent(keys, "ORIG_REALM", msg.originRealm());

        List<DiaAvp> avps = msg.avps() == null ? List.of() : msg.avps();

        String imsi = digitsOf(utf8Value(find(avps, AvpCodes.USER_NAME, 0)));
        if (imsi == null) {
            imsi = subscriptionData(avps, SUBSCRIPTION_TYPE_IMSI);
        }
        if (imsi != null) {
            keys.put("IMSI", imsi);
        }

        String msisdn = msisdnValue(find(avps, AvpCodes.MSISDN, AvpCodes.VENDOR_3GPP));
        if (msisdn == null) {
            msisdn = subscriptionData(avps, SUBSCRIPTION_TYPE_E164);
        }
        if (msisdn != null) {
            keys.put("MSISDN", msisdn);
        }

        String visitedPlmn = visitedPlmnValue(find(avps, AvpCodes.VISITED_PLMN_ID, AvpCodes.VENDOR_3GPP));
        if (visitedPlmn != null) {
            keys.put("VISITED_PLMN", visitedPlmn);
        }

        String framedIp = addressValue(find(avps, AvpCodes.FRAMED_IP_ADDRESS, 0));
        if (framedIp != null) {
            keys.put("FRAMED_IP", framedIp);
        }

        String apn = utf8Value(find(avps, AvpCodes.CALLED_STATION_ID, 0));
        if (apn != null) {
            keys.put("APN", apn);
        }
        return Map.copyOf(keys);
    }

    public static DiaAvp find(List<DiaAvp> avps, int code, int vendorId) {
        for (DiaAvp a : avps) {
            if (a.code() == code && a.vendorId() == vendorId) {
                return a;
            }
        }
        return null;
    }

    public static String utf8Value(DiaAvp avp) {
        if (avp == null) {
            return null;
        }
        if (avp.value() instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        if (avp.rawBytes() != null && avp.rawBytes().length > 0) {
            return new String(avp.rawBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        return null;
    }

    public static Long uint32Value(DiaAvp avp) {
        if (avp == null) {
            return null;
        }
        if (avp.value() instanceof Number n) {
            return n.longValue();
        }
        byte[] b = avp.rawBytes();
        if (b != null && b.length >= 4) {
            return ((long) (b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        }
        return null;
    }

    static String subscriptionData(List<DiaAvp> avps, int preferredType) {
        for (DiaAvp a : avps) {
            if (a.code() != AvpCodes.SUBSCRIPTION_ID || a.typeIndex() != DiaAvp.TYPE_GROUPED
                    || a.children() == null) {
                continue;
            }
            Integer type = null;
            String data = null;
            for (DiaAvp c : a.children()) {
                if (c.code() == AvpCodes.SUBSCRIPTION_ID_TYPE) {
                    type = uint32Value(c) == null ? null : uint32Value(c).intValue();
                } else if (c.code() == AvpCodes.SUBSCRIPTION_ID_DATA) {
                    data = utf8Value(c);
                }
            }
            if (type != null && type == preferredType && data != null) {
                return digitsOf(data);
            }
        }
        for (DiaAvp a : avps) {
            if (a.code() != AvpCodes.SUBSCRIPTION_ID || a.typeIndex() != DiaAvp.TYPE_GROUPED
                    || a.children() == null) {
                continue;
            }
            for (DiaAvp c : a.children()) {
                if (c.code() == AvpCodes.SUBSCRIPTION_ID_DATA) {
                    String d = digitsOf(utf8Value(c));
                    if (d != null) {
                        return d;
                    }
                }
            }
        }
        return null;
    }

    static String msisdnValue(DiaAvp avp) {
        if (avp == null) {
            return null;
        }
        if (avp.typeIndex() == DiaAvp.TYPE_UTF8 || avp.value() instanceof String) {
            return digitsOf(utf8Value(avp));
        }
        byte[] b = avp.rawBytes();
        if (b == null || b.length == 0) {
            return null;
        }
        return tbcdDigits(b, b.length * 2);
    }

    static String visitedPlmnValue(DiaAvp avp) {
        if (avp == null) {
            return null;
        }
        byte[] b = avp.rawBytes();
        if (b == null || b.length < 3) {
            String s = utf8Value(avp);
            return s == null ? null : digitsOnly(s);
        }
        int mcc1 = low(b[0]);
        int mcc2 = high(b[0]);
        int mcc3 = low(b[1]);
        int mnc3 = high(b[1]);
        int mnc1 = low(b[2]);
        int mnc2 = high(b[2]);
        if (!isDigit(mcc1) || !isDigit(mcc2) || !isDigit(mcc3)
                || !isDigit(mnc1) || !isDigit(mnc2)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(6);
        sb.append((char) ('0' + mcc1)).append((char) ('0' + mcc2)).append((char) ('0' + mcc3));
        sb.append((char) ('0' + mnc1)).append((char) ('0' + mnc2));
        if (mnc3 != 0xF && isDigit(mnc3)) {
            sb.append((char) ('0' + mnc3));
        }
        return sb.toString();
    }

    static String tbcdDigits(byte[] bytes, int maxDigits) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int lo = low(b);
            if (!isDigit(lo)) {
                break;
            }
            sb.append((char) ('0' + lo));
            if (sb.length() >= maxDigits) {
                break;
            }
            int hi = high(b);
            if (!isDigit(hi)) {
                break;
            }
            sb.append((char) ('0' + hi));
            if (sb.length() >= maxDigits) {
                break;
            }
        }
        String r = sb.toString();
        return r.isEmpty() ? null : r;
    }

    static String addressValue(DiaAvp avp) {
        if (avp == null) {
            return null;
        }
        if (avp.value() instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        byte[] b = avp.rawBytes();
        if (b == null || b.length == 0) {
            return null;
        }
        try {
            if (b.length >= 6) {
                int family = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                byte[] rest = new byte[b.length - 2];
                System.arraycopy(b, 2, rest, 0, rest.length);
                if (family == ADDRESS_FAMILY_IPV4 && rest.length == 4) {
                    return InetAddress.getByAddress(rest).getHostAddress();
                }
                if (family == ADDRESS_FAMILY_IPV6 && rest.length == 16) {
                    return Inet6Address.getByAddress(null, rest, null).getHostAddress();
                }
                return hexPairs(b);
            }
            if (b.length == 4 || b.length == 16) {
                return InetAddress.getByAddress(b).getHostAddress();
            }
        } catch (Exception ignored) {
            return hexPairs(b);
        }
        return hexPairs(b);
    }

    private static String hexPairs(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }

    private static String digitsOf(String s) {
        return s == null ? null : digitsOnly(s);
    }

    private static String digitsOnly(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void putIfPresent(Map<String, String> keys, String name, String value) {
        if (value != null && !value.isBlank()) {
            keys.put(name, value);
        }
    }

    private static int low(byte b) {
        return b & 0x0F;
    }

    private static int high(byte b) {
        return (b >> 4) & 0x0F;
    }

    private static boolean isDigit(int nibble) {
        return nibble >= 0 && nibble <= 9;
    }
}
