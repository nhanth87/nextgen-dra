package et.elisa.dra.core.bind;

import java.time.Instant;

public record BindingEntry(String key, String groupId, String peerId,
                           String originHost, String originRealm,
                           String ingressPeerId, Instant createdAt,
                           Instant expiresAt) {

    public boolean expiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }

    public BindingEntry withTtl(Instant newExpiry) {
        return new BindingEntry(key, groupId, peerId, originHost, originRealm,
                ingressPeerId, createdAt, newExpiry);
    }
}
