package et.elisa.dra.core.overload;

import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

public final class OverloadGateImpl implements OverloadGate {

    private final OlrCache olrCache;
    private final LoadCache loadCache;
    private final AdmissionController admission;
    private final Supplier<Instant> clock;

    public OverloadGateImpl(OlrCache olrCache, LoadCache loadCache,
                            AdmissionController admission) {
        this(olrCache, loadCache, admission, Instant::now);
    }

    public OverloadGateImpl(OlrCache olrCache, LoadCache loadCache,
                            AdmissionController admission, Supplier<Instant> clock) {
        this.olrCache = olrCache;
        this.loadCache = loadCache;
        this.admission = admission;
        this.clock = clock;
    }

    @Override
    public boolean admit(String ingressPeerId, int drmpPriority) {
        return tryAdmit(ingressPeerId, drmpPriority, -1);
    }

    public boolean tryAdmit(String ingressPeerId, int drmpPriority, int cmdCode) {
        double scale = 1.0d - olrCache.maxActiveReduction() / 100.0d;
        return admission.tryAcquire(ingressPeerId, drmpPriority, cmdCode,
                DrmpPolicy.CRITICAL_COMMANDS, scale);
    }

    @Override
    public void onAnswer(DiaMsg answerFromEgress, String egressPeerId) {
        List<DiaAvp> avps = answerFromEgress.avps() == null ? List.of() : answerFromEgress.avps();
        boolean doicCapable = avps.stream()
                .anyMatch(a -> a.code() == DoicAvps.OC_SUPPORTED_FEATURES);
        for (DiaAvp avp : avps) {
            switch (avp.code()) {
                case DoicAvps.OC_OLR -> {
                    if (doicCapable) {
                        handleOlr(avp, egressPeerId);
                    }
                }
                case DoicAvps.LOAD -> handleLoad(avp, egressPeerId);
                default -> {
                }
            }
        }
    }

    @Override
    public int reductionPercentFor(String egressPeerId) {
        return olrCache.reductionPercentFor(egressPeerId);
    }

    public OlrCache olrCache() {
        return olrCache;
    }

    public LoadCache loadCache() {
        return loadCache;
    }

    private void handleOlr(DiaAvp olr, String reportingPeerId) {
        long seq = childUint(olr, DoicAvps.OC_SEQUENCE_NUMBER);
        if (seq < 0) {
            return;
        }
        int reportType = (int) childUintOr(olr, DoicAvps.OC_REPORT_TYPE, DoicAvps.REPORT_HOST);
        int reduction = (int) childUintOr(olr, DoicAvps.OC_REDUCTION_PERCENTAGE, 0);
        long validitySec = childUintOr(olr, DoicAvps.OC_VALIDITY_DURATION,
                DoicAvps.DEFAULT_VALIDITY_SECONDS);
        Instant validUntil = clock.get().plusSeconds(validitySec);
        olrCache.update(reportingPeerId, seq, reportType, reduction, validUntil);
    }

    private void handleLoad(DiaAvp load, String egressPeerId) {
        int type = (int) childUintOr(load, DoicAvps.LOAD_TYPE, -1);
        long value = childUint(load, DoicAvps.LOAD_VALUE);
        if (type == LoadCache.LOAD_TYPE_HOST && value >= 0) {
            loadCache.hostLoad(egressPeerId, (int) value);
        } else if (type == LoadCache.LOAD_TYPE_PEER && value >= 0) {
            loadCache.peerReportObserved();
        }
    }

    private static long childUint(DiaAvp grouped, int childCode) {
        return childUintOr(grouped, childCode, -1);
    }

    private static long childUintOr(DiaAvp grouped, int childCode, long fallback) {
        List<DiaAvp> children = grouped.children();
        if (children == null) {
            return fallback;
        }
        for (DiaAvp c : children) {
            if (c.code() == childCode && c.value() instanceof Number n) {
                return n.longValue();
            }
        }
        return fallback;
    }
}
