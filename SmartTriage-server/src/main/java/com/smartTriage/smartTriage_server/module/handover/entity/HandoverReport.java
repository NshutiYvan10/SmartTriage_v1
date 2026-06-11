package com.smartTriage.smartTriage_server.module.handover.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.HandoverReportType;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * HandoverReport — auto-generated comprehensive patient summary for shift
 * handovers, ward transfers, discharge summaries, and inter-hospital transfers.
 *
 * Each report compiles all clinical data from the visit into structured sections
 * to ensure safe continuity of care during transitions.
 */
@Entity
@Table(name = "handover_reports", indexes = {
        @Index(name = "idx_handover_visit", columnList = "visit_id"),
        @Index(name = "idx_handover_hospital", columnList = "hospital_id"),
        @Index(name = "idx_handover_type", columnList = "report_type"),
        @Index(name = "idx_handover_generated_at", columnList = "generated_at"),
        @Index(name = "idx_handover_acknowledged", columnList = "is_acknowledged"),
        @Index(name = "idx_handover_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HandoverReport extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 30)
    private HandoverReportType reportType;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "generated_by_name", length = 255)
    private String generatedByName;

    // ====================================================================
    // CONTENT SECTIONS
    // ====================================================================

    @Column(name = "patient_summary", columnDefinition = "TEXT")
    private String patientSummary;

    @Column(name = "presenting_complaint", columnDefinition = "TEXT")
    private String presentingComplaint;

    @Column(name = "triage_summary", columnDefinition = "TEXT")
    private String triageSummary;

    @Column(name = "vital_signs_trend", columnDefinition = "TEXT")
    private String vitalSignsTrend;

    @Column(name = "investigations_results", columnDefinition = "TEXT")
    private String investigationsResults;

    @Column(name = "diagnosis_summary", columnDefinition = "TEXT")
    private String diagnosisSummary;

    @Column(name = "treatment_summary", columnDefinition = "TEXT")
    private String treatmentSummary;

    @Column(name = "active_clinical_alerts", columnDefinition = "TEXT")
    private String activeClinicalAlerts;

    @Column(name = "outstanding_tasks", columnDefinition = "TEXT")
    private String outstandingTasks;

    @Column(name = "plan_of_care", columnDefinition = "TEXT")
    private String planOfCare;

    @Column(name = "ed_timeline", columnDefinition = "TEXT")
    private String edTimeline;

    /**
     * V67 — full medication audit trail at generation time: active
     * orders with schedule + remaining doses, every dose given (by
     * whom, when, witness), missed / held / refused / discontinued
     * with reasons, and the modification chain. The incoming doctor
     * must have zero ambiguity about the medication history.
     */
    @Column(name = "medication_audit", columnDefinition = "TEXT")
    private String medicationAudit;

    // ====================================================================
    // ACKNOWLEDGMENT
    // ====================================================================

    @Column(name = "received_by_name", length = 255)
    private String receivedByName;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "is_acknowledged", nullable = false)
    @Builder.Default
    private boolean isAcknowledged = false;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
