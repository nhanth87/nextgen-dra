package et.elisa.dra.app.persist;

import et.elisa.dra.core.bind.BindingEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "dra_binding")
public class DraBindingEntity {

    @Id
    @Column(name = "\"key\"", nullable = false, unique = true)
    private String bindingKey;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(name = "peer_id", nullable = false)
    private String peerId;

    @Column(name = "origin_host")
    private String originHost;

    @Column(name = "origin_realm")
    private String originRealm;

    @Column(name = "ingress_peer_id")
    private String ingressPeerId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected DraBindingEntity() {
    }

    public DraBindingEntity(String bindingKey, String groupId, String peerId,
                            String originHost, String originRealm, String ingressPeerId,
                            Instant createdAt, Instant expiresAt) {
        this.bindingKey = bindingKey;
        this.groupId = groupId;
        this.peerId = peerId;
        this.originHost = originHost;
        this.originRealm = originRealm;
        this.ingressPeerId = ingressPeerId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static DraBindingEntity from(BindingEntry entry) {
        return new DraBindingEntity(entry.key(), entry.groupId(), entry.peerId(),
                entry.originHost(), entry.originRealm(), entry.ingressPeerId(),
                entry.createdAt(), entry.expiresAt());
    }

    public BindingEntry toEntry() {
        return new BindingEntry(bindingKey, groupId, peerId, originHost, originRealm,
                ingressPeerId, createdAt, expiresAt);
    }

    public String getBindingKey() {
        return bindingKey;
    }

    public void setBindingKey(String bindingKey) {
        this.bindingKey = bindingKey;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public String getOriginHost() {
        return originHost;
    }

    public void setOriginHost(String originHost) {
        this.originHost = originHost;
    }

    public String getOriginRealm() {
        return originRealm;
    }

    public void setOriginRealm(String originRealm) {
        this.originRealm = originRealm;
    }

    public String getIngressPeerId() {
        return ingressPeerId;
    }

    public void setIngressPeerId(String ingressPeerId) {
        this.ingressPeerId = ingressPeerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
