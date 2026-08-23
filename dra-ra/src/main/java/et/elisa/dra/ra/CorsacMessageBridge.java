package et.elisa.dra.ra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mobius.software.telco.protocols.diameter.commands.DiameterAnswer;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.AvpNotSupportedException;
import com.mobius.software.telco.protocols.diameter.parser.DiameterParser;
import com.mobius.software.telco.protocols.diameter.primitives.DiameterAvpKey;
import com.mobius.software.telco.protocols.diameter.primitives.DiameterUnknownAvp;

import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import et.elisa.dra.ra.wire.DiameterWireCodec;
import io.netty.buffer.ByteBuf;

public final class CorsacMessageBridge {

    public static final int CMD_CAPABILITIES_EXCHANGE = 257;
    public static final int CMD_DEVICE_WATCHDOG = 280;
    public static final int CMD_DISCONNECT_PEER = 282;

    private CorsacMessageBridge() {
    }

    public static boolean isBaseProtocol(DiameterMessage message) {
        var def = DiameterParser.getCommandDefinition(message.getClass());
        if (def == null) {
            return false;
        }
        int code = def.commandCode();
        return code == CMD_CAPABILITIES_EXCHANGE || code == CMD_DEVICE_WATCHDOG
                || code == CMD_DISCONNECT_PEER;
    }

    public static IngressEvent toIngressEvent(DiameterMessage message, String linkId, long receivedNanos) {
        DiaMsg wire = toDiaMsg(message);
        if (wire.isRequest()) {
            return new IngressRequest(wire, linkId, receivedNanos);
        }
        return new IngressAnswer(wire, linkId, receivedNanos);
    }

    public static DiaMsg toDiaMsg(DiameterMessage message) {
        CorsacHeader header = headerOf(message);
        boolean request = message instanceof DiameterRequest;
        var def = DiameterParser.getCommandDefinition(message.getClass());
        int commandCode = def != null ? def.commandCode() : header.commandCode();
        long applicationId = def != null ? def.applicationId() : header.applicationId();
        int flags = (request ? DiaMsg.FLAG_REQUEST : 0)
                | (Boolean.TRUE.equals(message.getIsProxyable()) ? DiaMsg.FLAG_PROXYABLE : 0)
                | (Boolean.TRUE.equals(message.getIsRetransmit()) ? DiaMsg.FLAG_RETRANSMIT : 0);
        long hbh = message.getHopByHopIdentifier() != null
                ? message.getHopByHopIdentifier() : header.hopByHopId();
        long e2e = message.getEndToEndIdentifier() != null
                ? message.getEndToEndIdentifier() : header.endToEndId();
        long resultCode = 0L;
        if (message instanceof DiameterAnswer answer && answer.getResultCode() != null) {
            resultCode = answer.getResultCode();
        }

        List<DiaAvp> avps = new ArrayList<>();
        addUtf8(avps, AvpCodes.SESSION_ID, safeSessionId(message), true);
        addUtf8(avps, AvpCodes.USER_NAME, safeUsername(message), false);
        addUtf8(avps, AvpCodes.ORIGIN_HOST, message.getOriginHost(), true);
        addUtf8(avps, AvpCodes.ORIGIN_REALM, message.getOriginRealm(), true);
        if (message instanceof DiameterRequest req) {
            addUtf8(avps, AvpCodes.DESTINATION_HOST, req.getDestinationHost(), true);
            addUtf8(avps, AvpCodes.DESTINATION_REALM, req.getDestinationRealm(), true);
        }
        if (!request && resultCode > 0L) {
            avps.add(DiaAvp.uint32(AvpCodes.RESULT_CODE, resultCode));
        }
        appendRawOptionalAvps(message, avps);

        return new DiaMsg(header.version(), flags, commandCode, (int) applicationId,
                hbh, e2e,
                sessionIdOf(message),
                orEmpty(message.getOriginHost()), orEmpty(message.getOriginRealm()),
                destinationHostOf(message), destinationRealmOf(message),
                (int) resultCode, List.copyOf(avps));
    }

    public static DiaMsg fromRawFrame(ByteBuf frame) {
        return DiameterWireCodec.decode(frame);
    }

    private static void appendRawOptionalAvps(DiameterMessage message, List<DiaAvp> out) {
        Map<DiameterAvpKey, List<DiameterUnknownAvp>> optional = safeOptionalAvps(message);
        if (optional == null || optional.isEmpty()) {
            return;
        }
        for (Map.Entry<DiameterAvpKey, List<DiameterUnknownAvp>> entry : optional.entrySet()) {
            DiameterAvpKey key = entry.getKey();
            int code = key.getAvpID() == null ? 0 : (int) key.getAvpID().longValue();
            long vendor = key.getVendorID() == null ? 0L : key.getVendorID();
            List<DiameterUnknownAvp> values = entry.getValue();
            if (values == null) {
                continue;
            }
            for (DiameterUnknownAvp unknown : values) {
                byte[] bytes = bytesOf(unknown);
                if (bytes != null) {
                    boolean mandatory = Boolean.TRUE.equals(unknown.getIsMust());
                    out.add(new DiaAvp(code, (int) vendor, mandatory,
                            DiaAvp.TYPE_OCTETS, null, bytes, null));
                }
            }
        }
    }

    private static Map<DiameterAvpKey, List<DiameterUnknownAvp>> safeOptionalAvps(DiameterMessage message) {
        try {
            return message.getOptionalAvps();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static byte[] bytesOf(DiameterUnknownAvp unknown) {
        try {
            ByteBuf buf = unknown.getValue();
            if (buf == null) {
                return null;
            }
            byte[] out = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), out);
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static CorsacHeader headerOf(DiameterMessage message) {
        try {
            ByteBuf buffer = message.getBuffer();
            if (buffer != null && buffer.readableBytes() >= DiameterWireCodec.HEADER_LENGTH) {
                var h = DiameterWireCodec.peekHeader(buffer.duplicate());
                return new CorsacHeader(h.version(), h.flags(), h.commandCode(),
                        h.applicationId(), h.hopByHopId(), h.endToEndId());
            }
        } catch (RuntimeException e) {
            return CorsacHeader.ZERO;
        }
        return CorsacHeader.ZERO;
    }

    private static String safeSessionId(DiameterMessage message) {
        try {
            String sid = message.getSessionId();
            return sid == null ? "" : sid;
        } catch (AvpNotSupportedException e) {
            return "";
        }
    }

    private static String safeUsername(DiameterMessage message) {
        try {
            return message.getUsername();
        } catch (AvpNotSupportedException | RuntimeException e) {
            return "";
        }
    }

    private static String sessionIdOf(DiameterMessage message) {
        return safeSessionId(message);
    }

    private static String destinationHostOf(DiameterMessage message) {
        if (message instanceof DiameterRequest req) {
            try {
                return orEmpty(req.getDestinationHost());
            } catch (RuntimeException e) {
                return "";
            }
        }
        return "";
    }

    private static String destinationRealmOf(DiameterMessage message) {
        if (message instanceof DiameterRequest req) {
            try {
                return orEmpty(req.getDestinationRealm());
            } catch (RuntimeException e) {
                return "";
            }
        }
        return "";
    }

    private static void addUtf8(List<DiaAvp> out, int code, String value, boolean mandatory) {
        if (value != null && !value.isBlank()) {
            out.add(new DiaAvp(code, 0, mandatory, DiaAvp.TYPE_UTF8, value, null, null));
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private record CorsacHeader(int version, int flags, int commandCode,
                                long applicationId, long hopByHopId, long endToEndId) {
        private static final CorsacHeader ZERO =
                new CorsacHeader(1, 0, 0, 0L, 0L, 0L);
    }
}
