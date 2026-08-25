package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.engine.AvpOp;
import et.elisa.dra.core.engine.RoutingContext;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AvpOps {

    private AvpOps() {
    }

    public static DiaMsg apply(DiaMsg msg, List<AvpOp> ops) {
        DiaMsg out = msg;
        for (AvpOp op : ops) {
            out = switch (op) {
                case AvpOp.AppendRouteRecord a -> append(out, DiaAvp.utf8(AvpCodes.ROUTE_RECORD, a.host()));
                case AvpOp.Set s -> append(drop(out, s.code(), s.vendorId()), convert(s));
                case AvpOp.Drop d -> drop(out, d.code(), d.vendorId());
            };
        }
        return out;
    }

    public static DiaMsg withDestinationHost(DiaMsg msg, String host) {
        DiaMsg stripped = drop(msg, AvpCodes.DESTINATION_HOST, 0);
        return rebuild(stripped, host, append(stripped.avps(), DiaAvp.utf8(AvpCodes.DESTINATION_HOST, host)));
    }

    public static DiaMsg append(DiaMsg msg, DiaAvp avp) {
        return rebuild(msg, msg.destinationHost(), append(msg.avps(), avp));
    }

    public static DiaMsg drop(DiaMsg msg, int code, int vendorId) {
        List<DiaAvp> kept = new ArrayList<>(msg.avps().size());
        boolean changed = false;
        for (DiaAvp a : msg.avps()) {
            if (a.code() == code && a.vendorId() == vendorId) {
                changed = true;
                continue;
            }
            kept.add(a);
        }
        return changed ? rebuild(msg, msg.destinationHost(), List.copyOf(kept)) : msg;
    }

    public static Optional<String> firstUtf8(DiaMsg msg, int code) {
        for (DiaAvp a : msg.avps()) {
            if (a.code() == code && a.value() instanceof String s) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public static Optional<Long> firstUint32(DiaMsg msg, int code) {
        for (DiaAvp a : msg.avps()) {
            if (a.code() == code && a.value() instanceof Long v) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    public static List<String> stringsOf(DiaMsg msg, int code) {
        List<String> out = List.of();
        for (DiaAvp a : msg.avps()) {
            if (a.code() == code && a.value() instanceof String s) {
                out = append(out, s);
            }
        }
        return out;
    }

    public static int drmpPriority(DiaMsg msg) {
        Long raw = firstUint32(msg, AvpCodes.DRMP).orElse(null);
        if (raw != null) {
            return (int) (long) raw;
        }
        return firstUtf8(msg, AvpCodes.DRMP)
                .map(s -> {
                    try {
                        return Integer.parseInt(s.trim());
                    } catch (NumberFormatException e) {
                        return RoutingContext.DRMP_DEFAULT;
                    }
                })
                .orElse(RoutingContext.DRMP_DEFAULT);
    }

    private static DiaAvp convert(AvpOp.Set op) {
        if (op.typeIndex() == DiaAvp.TYPE_UINT32 || op.typeIndex() == DiaAvp.TYPE_INT32) {
            return DiaAvp.uint32(op.code(), Long.parseLong(op.value()));
        }
        return DiaAvp.utf8(op.code(), op.value());
    }

    private static DiaMsg rebuild(DiaMsg m, String destinationHost, List<DiaAvp> avps) {
        return new DiaMsg(m.version(), m.flags(), m.commandCode(), m.applicationId(),
                m.hopByHopId(), m.endToEndId(), m.sessionId(), m.originHost(), m.originRealm(),
                destinationHost, m.destinationRealm(), m.resultCode(), avps);
    }

    private static <T> List<T> append(List<T> list, T item) {
        List<T> out = new ArrayList<>(list.size() + 1);
        out.addAll(list);
        out.add(item);
        return List.copyOf(out);
    }
}
