package com.smartTriage.smartTriage_server.module.audit.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * AuditLog — a persisted, server-backed record of every state-changing action,
 * captured automatically by {@code AuditInterceptor} (who did what, when, to which
 * endpoint, with what outcome). This replaces the old browser-only, session-scoped
 * audit store that lost everything on reload and exported an empty CSV.
 *
 * <p>Audit rows are immutable: they are only ever inserted, never updated or
 * deleted. The timestamp is the inherited server-set {@code createdAt}.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_hospital_time", columnList = "hospital_id, created_at"),
        @Index(name = "idx_audit_actor", columnList = "actor_user_id"),
        @Index(name = "idx_audit_action", columnList = "action")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog extends BaseEntity {

    /** The authenticated actor (null only for unauthenticated/system calls). */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_name")
    private String actorName;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    /** Hospital the actor belongs to — used to scope the audit report per tenant. */
    @Column(name = "hospital_id")
    private UUID hospitalId;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "path", length = 512)
    private String path;

    /** Human-readable action label derived from method+path (e.g. "Record disposition"). */
    @Column(name = "action", length = 120)
    private String action;

    @Column(name = "status_code")
    private Integer statusCode;

    /** SUCCESS when the request returned 2xx/3xx, otherwise FAILED. */
    @Column(name = "outcome", length = 12)
    private String outcome;
}
