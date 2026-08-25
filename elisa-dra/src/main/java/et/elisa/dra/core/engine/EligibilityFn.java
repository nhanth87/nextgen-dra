package et.elisa.dra.core.engine;

import et.elisa.dra.core.lb.PeerHandle;

@FunctionalInterface
public interface EligibilityFn {

    boolean eligible(PeerHandle peer, int appId);

    static EligibilityFn all() {
        return (peer, appId) -> true;
    }
}
