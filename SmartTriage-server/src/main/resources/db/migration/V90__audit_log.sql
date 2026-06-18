-- V90: Persisted, server-backed audit log (reporting gap R3).
--
-- The Audit Trail page was fed by a browser-only Zustand store (session-scoped,
-- empty on reload, CSV export produced an empty file) — useless for the auditor /
-- governance personas whose core job is retrospective oversight. This table is
-- written automatically by AuditInterceptor for every state-changing request:
-- who (actor + role), when (created_at), what (method + path + action label),
-- outcome (status). Rows are insert-only (immutable).

CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    actor_user_id   UUID,
    actor_name      VARCHAR(255),
    actor_role      VARCHAR(30),
    hospital_id     UUID,
    http_method     VARCHAR(10),
    path            VARCHAR(512),
    action          VARCHAR(120),
    status_code     INTEGER,
    outcome         VARCHAR(12)
);

CREATE INDEX idx_audit_hospital_time ON audit_logs(hospital_id, created_at);
CREATE INDEX idx_audit_actor ON audit_logs(actor_user_id);
CREATE INDEX idx_audit_action ON audit_logs(action);
