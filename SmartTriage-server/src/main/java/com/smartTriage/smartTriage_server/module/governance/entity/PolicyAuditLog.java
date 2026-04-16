package com.smartTriage.smartTriage_server.module.governance.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Audit log entry for clinical policy changes.
 * Tracks every state transition and content modification.
 */
@Entity
@Table(name = "policy_audit_logs", indexes = {
        @Index(name = "idx_policy_audit_policy", columnList = "policy_id"),
        @Index(name = "idx_policy_audit_action_at", columnList = "action_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyAuditLog extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private ClinicalPolicy policy;

    @Column(name = "action", nullable = false, length = 30)
    private String action;

    @Column(name = "action_at", nullable = false)
    private Instant actionAt;

    @Column(name = "action_by_name", nullable = false)
    private String actionByName;

    @Column(name = "previous_content", columnDefinition = "TEXT")
    private String previousContent;

    @Column(name = "new_content", columnDefinition = "TEXT")
    private String newContent;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;
}
