package et.elisa.dra.core.overload;

import et.elisa.dra.core.lb.PeerHandle;

public final class OverloadEligibility {

    private OverloadEligibility() {
    }

    public static boolean eligible(PeerHandle peer, int reductionPercent, int threshold) {
        return peer.healthy() && reductionPercent < threshold;
    }
}
