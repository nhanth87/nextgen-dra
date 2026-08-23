-- Elisa Nextgen DRA baseline schema (PostgreSQL)

CREATE TABLE dra_binding (
    key             TEXT PRIMARY KEY,
    group_id        TEXT NOT NULL,
    peer_id         TEXT NOT NULL,
    origin_host     TEXT,
    origin_realm    TEXT,
    ingress_peer_id TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_dra_binding_expires_at ON dra_binding (expires_at);

CREATE TABLE route_config (
    id         BIGSERIAL PRIMARY KEY,
    version    INT NOT NULL UNIQUE,
    payload    JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id        BIGSERIAL PRIMARY KEY,
    ts        TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor     TEXT NOT NULL,
    action    TEXT NOT NULL,
    diff_json JSONB
);

CREATE INDEX idx_audit_log_ts ON audit_log (ts DESC);
