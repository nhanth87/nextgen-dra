package et.elisa.dra.core.wire;

import java.util.List;

public record DiaMsg(int version, int flags, int commandCode, int applicationId,
                     long hopByHopId, long endToEndId, String sessionId,
                     String originHost, String originRealm,
                     String destinationHost, String destinationRealm,
                     int resultCode, List<DiaAvp> avps) {

    public static final int FLAG_REQUEST = 0x80;
    public static final int FLAG_PROXYABLE = 0x40;
    public static final int FLAG_ERROR = 0x20;
    public static final int FLAG_RETRANSMIT = 0x10;

    public boolean isRequest() {
        return (flags & FLAG_REQUEST) != 0;
    }

    public DiaMsg withHopByHop(long newHbh) {
        return new DiaMsg(version, flags, commandCode, applicationId, newHbh,
                endToEndId, sessionId, originHost, originRealm,
                destinationHost, destinationRealm, resultCode, avps);
    }

    public DiaMsg asAnswer(int code) {
        return new DiaMsg(version, flags & ~FLAG_REQUEST, commandCode,
                applicationId, hopByHopId, endToEndId, sessionId, originHost,
                originRealm, destinationHost, destinationRealm, code, avps);
    }

    public DiaMsg withOrigin(String host, String realm) {
        return new DiaMsg(version, flags, commandCode, applicationId, hopByHopId,
                endToEndId, sessionId, host, realm, destinationHost,
                destinationRealm, resultCode, avps);
    }
}
