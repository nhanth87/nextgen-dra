package et.elisa.dra.app.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant ts;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff_json", columnDefinition = "jsonb")
    private String diffJson;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(Instant ts, String actor, String action, String diffJson) {
        this.ts = ts;
        this.actor = actor;
        this.action = action;
        this.diffJson = diffJson;
    }

    public Long getId() {
        return id;
    }

    public Instant getTs() {
        return ts;
    }

    public void setTs(Instant ts) {
        this.ts = ts;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDiffJson() {
        return diffJson;
    }

    public void setDiffJson(String diffJson) {
        this.diffJson = diffJson;
    }
}
