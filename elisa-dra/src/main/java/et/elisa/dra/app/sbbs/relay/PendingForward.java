package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.wire.DiaMsg;

record PendingForward(DiaMsg originalRequest, DiaMsg outboundBody, String stickyKeyFull,
                      String groupId, long ttlSeconds, String originHost, String originRealm,
                      boolean thEnabled) {
}
