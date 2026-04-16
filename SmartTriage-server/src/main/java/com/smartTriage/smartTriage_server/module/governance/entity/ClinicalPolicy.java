package com.smartTriage.smartTriage_server.module.governance.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.PolicyStatus;
import com.smartTriage.smartTriage_server.common.enums.PolicyType;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Clinical Policy — represents a triage rule, drug protocol, clinical guideline, or other
 * governance policy that can be created, reviewed, approved, activated, and versioned.
 * Null hospital means system-wide default policy.
 */
@Entity
@Table(name = "clinical_policies", indexes = {
        @Index(name = "idx_clinical_policy_hospital", columnList = "hospital_id"),
        @Index(name = "idx_clinical_policy_type", columnList = "policy_type"),
        @Index(name = "idx_clinical_policy_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClinicalPolicy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_type", nullable = false, length = 30)
    private PolicyType policyType;

    @Column(name = "policy_name", nullable = false)
    private String policyName;

    @Column(name = "policy_code")
    private String policyCode;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ---- Policy data ----

    @Column(name = "policy_content", nullable = false, columnDefinition = "TEXT")
    private String policyContent;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "policy_version", length = 20)
    private String policyVersion;

    // ---- Approval workflow ----

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private PolicyStatus status = PolicyStatus.DRAFT;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "approved_by_name")
    private String approvedByName;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_notes", columnDefinition = "TEXT")
    private String approvalNotes;

    // ---- Audit ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_version_id")
    private ClinicalPolicy previousVersion;

    @Column(name = "change_reason", columnDefinition = "TEXT")
    private String changeReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
