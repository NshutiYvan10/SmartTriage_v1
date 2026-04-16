package com.smartTriage.smartTriage_server.module.icu.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.IcuEscalationStatus;
import com.smartTriage.smartTriage_server.common.enums.IcuTriggerType;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * IcuEscalation entity — tracks the full lifecycle of an ICU escalation request.
 *
 * An escalation can be triggered automatically by the ICU detection engine
 * (based on vital sign thresholds) or manually by a clinician. It progresses
 * through: REQUESTED → ICU_NOTIFIED → ICU_ACCEPTED/DECLINED → TRANSFERRED_TO_ICU.
 *
 * In resource-constrained Rwanda hospitals, ICU beds are scarce. This module
 * ensures proper documentation of escalation decisions and alternative plans
 * when ICU beds are unavailable.
 */
@Entity
@Table(name = "icu_escalations", indexes = {
        @Index(name = "idx_icu_escalation_visit", columnList = "visit_id"),
        @Index(name = "idx_icu_escalation_status", columnList = "status"),
        @Index(name = "idx_icu_escalation_escalated_at", columnList = "escalated_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IcuEscalation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "escalation_reason", nullable = false, columnDefinition = "TEXT")
    private String escalationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", length = 30)
    private IcuTriggerType triggerType;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "escalated_by_name")
    private String escalatedByName;

    @Column(name = "is_automatic", nullable = false)
    @Builder.Default
    private boolean isAutomatic = false;

    // --- ICU Team Response ---

    @Column(name = "icu_team_notified_at")
    private Instant icuTeamNotifiedAt;

    @Column(name = "icu_consultant")
    private String icuConsultant;

    @Column(name = "icu_responded_at")
    private Instant icuRespondedAt;

    @Column(name = "icu_response_minutes")
    private Integer icuResponseMinutes;

    @Column(name = "icu_bed_available")
    private Boolean icuBedAvailable;

    @Column(name = "icu_bed_number")
    private String icuBedNumber;

    @Column(name = "icu_bed_assigned_at")
    private Instant icuBedAssignedAt;

    // --- Stabilization ---

    @Column(name = "stabilization_started_at")
    private Instant stabilizationStartedAt;

    @Column(name = "stabilization_notes", columnDefinition = "TEXT")
    private String stabilizationNotes;

    @Column(name = "intubation_required")
    private Boolean intubationRequired;

    @Column(name = "vasopressors_required")
    private Boolean vasopressorsRequired;

    @Column(name = "mechanical_ventilation")
    private Boolean mechanicalVentilation;

    // --- Status and Outcome ---

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private IcuEscalationStatus status = IcuEscalationStatus.REQUESTED;

    @Column(name = "decline_reason", columnDefinition = "TEXT")
    private String declineReason;

    @Column(name = "transferred_at")
    private Instant transferredAt;

    @Column(name = "alternative_plan", columnDefinition = "TEXT")
    private String alternativePlan;

    @Column(name = "outcome", columnDefinition = "TEXT")
    private String outcome;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
