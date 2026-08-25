package et.elisa.dra.core.th;

import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TopologyHiderImpl implements TopologyHider {

    private static final int CODE_ROUTE_RECORD = 282;
    private static final int CODE_SESSION_ID = 263;

    private final PseudoHostMapper mapper;
    private final ThMetrics metrics = new ThMetrics();
    private final ConcurrentMap<String, String> learnedRealByPseudo = new ConcurrentHashMap<>();
    private final String selfOriginHost;

    public TopologyHiderImpl(PseudoHostMapper mapper, String selfOriginHost) {
        this.mapper = mapper;
        this.selfOriginHost = selfOriginHost;
    }

    @Override
    public boolean enabledForGroup(String groupId) {
        return mapper.config().enabledFor(groupId);
    }

    @Override
    public DiaMsg hideOutbound(DiaMsg request, String deterministicKey) {
        String pseudo = mapper.pseudoFor(deterministicKey);

        String originHost = request.originHost();
        if (mapper.isInternalHost(originHost)) {
            learnedRealByPseudo.put(pseudo, originHost);
            originHost = pseudo;
        }
        String destHost = request.destinationHost();
        if (mapper.isInternalHost(destHost)) {
            String destPseudo = mapper.pseudoFor(destHost);
            learnedRealByPseudo.putIfAbsent(destPseudo, destHost);
            destHost = destPseudo;
        }
        String sessionId = rewriteSessionId(request.sessionId(), pseudo);
        List<DiaAvp> avps = transformAvps(request.avps(), true);

        metrics.hideTotal.increment();
        return new DiaMsg(request.version(), request.flags(), request.commandCode(),
                request.applicationId(), request.hopByHopId(), request.endToEndId(),
                sessionId, originHost, request.originRealm(), destHost,
                request.destinationRealm(), request.resultCode(), avps);
    }

    @Override
    public DiaMsg restoreInbound(DiaMsg message) {
        String originHost = message.originHost();
        if (isPseudoHost(originHost)) {
            originHost = learnedRealByPseudo.getOrDefault(originHost, originHost);
            if (originHost.equals(message.originHost())) {
                metrics.restoreMiss.increment();
            }
        }
        String sessionId = restoreSessionId(message.sessionId());
        List<DiaAvp> avps = new ArrayList<>(transformAvps(message.avps(), false));
        avps.add(DiaAvp.utf8(CODE_ROUTE_RECORD, selfOriginHost));

        metrics.restoreTotal.increment();
        return new DiaMsg(message.version(), message.flags(), message.commandCode(),
                message.applicationId(), message.hopByHopId(), message.endToEndId(),
                sessionId, originHost, message.originRealm(), message.destinationHost(),
                message.destinationRealm(), message.resultCode(), List.copyOf(avps));
    }

    public ThMetrics metrics() {
        return metrics;
    }

    private String rewriteSessionId(String sessionId, String pseudo) {
        if (sessionId == null || sessionId.isBlank()) {
            return sessionId;
        }
        int idx = sessionId.indexOf(';');
        String hostPart = idx < 0 ? sessionId : sessionId.substring(0, idx);
        if (mapper.isInternalHost(hostPart)) {
            learnedRealByPseudo.putIfAbsent(pseudo, hostPart);
        } else if (!isPseudoHost(hostPart)) {
            return sessionId;
        }
        return idx < 0 ? pseudo : pseudo + sessionId.substring(idx);
    }

    private String restoreSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return sessionId;
        }
        int idx = sessionId.indexOf(';');
        String hostPart = idx < 0 ? sessionId : sessionId.substring(0, idx);
        if (!isPseudoHost(hostPart)) {
            return sessionId;
        }
        String real = learnedRealByPseudo.get(hostPart);
        if (real == null) {
            metrics.restoreMiss.increment();
            return sessionId;
        }
        return idx < 0 ? real : real + sessionId.substring(idx);
    }

    private List<DiaAvp> transformAvps(List<DiaAvp> avps, boolean outbound) {
        if (avps == null || avps.isEmpty()) {
            return avps == null ? List.of() : avps;
        }
        boolean fullEdge = mapper.config().fullEdge();
        List<DiaAvp> out = new ArrayList<>(avps.size());
        for (DiaAvp avp : avps) {
            if (outbound && avp.code() == CODE_ROUTE_RECORD) {
                continue;
            }
            out.add(transformAvp(avp, outbound, fullEdge));
        }
        return List.copyOf(out);
    }

    private DiaAvp transformAvp(DiaAvp avp, boolean outbound, boolean fullEdge) {
        DiaAvp current = avp;
        if (current.children() != null) {
            List<DiaAvp> transformedChildren = new ArrayList<>(current.children().size());
            for (DiaAvp child : current.children()) {
                transformedChildren.add(transformAvp(child, outbound, fullEdge));
            }
            current = new DiaAvp(current.code(), current.vendorId(), current.mandatory(),
                    current.typeIndex(), current.value(), current.rawBytes(),
                    List.copyOf(transformedChildren));
        }
        if (fullEdge && current.typeIndex() == DiaAvp.TYPE_UTF8
                && current.value() instanceof String s
                && leaksInternalIdentity(s)) {
            String replacement = replaceIdentity(s);
            if (!replacement.equals(s)) {
                metrics.leakBlocked.increment();
                current = withValue(current, replacement);
            }
        }
        return current;
    }

    private boolean leaksInternalIdentity(String s) {
        int idx = s.indexOf(';');
        String candidate = idx < 0 ? s : s.substring(0, idx);
        return !candidate.isBlank()
                && !candidate.chars().anyMatch(c -> Character.isWhitespace(c)
                        || c == '/' || c == '@' || c == ':')
                && mapper.isInternalHost(candidate);
    }

    private String replaceIdentity(String value) {
        int idx = value.indexOf(';');
        String hostPart = idx < 0 ? value : value.substring(0, idx);
        String mapped;
        if (mapper.isInternalHost(hostPart)) {
            mapped = mapper.pseudoFor(hostPart);
            learnedRealByPseudo.putIfAbsent(mapped, hostPart);
        } else if (isPseudoHost(hostPart)) {
            mapped = learnedRealByPseudo.getOrDefault(hostPart, hostPart);
        } else {
            return value;
        }
        return idx < 0 ? mapped : mapped + value.substring(idx);
    }

    private boolean isPseudoHost(String host) {
        return mapper.realFor(host).isPresent();
    }

    private DiaAvp withValue(DiaAvp avp, Object newValue) {
        return new DiaAvp(avp.code(), avp.vendorId(), avp.mandatory(),
                avp.typeIndex(), newValue, null, avp.children());
    }
}
