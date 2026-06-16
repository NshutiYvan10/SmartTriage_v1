package com.smartTriage.smartTriage_server.module.hypoglycemia.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * HypoglycemiaEvent — records a detected hypoglycemia event and its management.
 *
 * Per Rwanda protocol:
 * - CRITICAL: glucose < 3.0 mmol/L → immediate treatment required
 *   - Adults: 50mL 50% dextrose IV
 *   - Children: 5mL/kg 10% dextrose
 * - MILD: glucose 3.0-3.9 mmol/L → close monitoring, oral glucose if conscious
 * - NORMAL: glucose >= 4.0 mmol/L
 */
@Entity
@Table(name = "hypoglycemia_events", indexes = {
        @Index(name = "idx_hypo_visit", columnList = "visit_id"),
        @Index(name = "idx_hypo_severity", columnList = "severity"),
        @Index(name = "idx_hypo_resolved", columnList = "resolved"),
        @Index(name = "idx_hypo_detected_at", columnList = "detected_at"),
        @Index(name = "idx_hypo_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HypoglycemiaEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** Glucose level in mmol/L */
    @Column(name = "glucose_level")
    private Double glucoseLevel;

    /** What triggered the glucose check: altered_consciousness, convulsion, coma, diabetic, etc. */
    @Column(name = "trigger_reason", nullable = false)
    private String triggerReason;

    /** HypoglycemiaSeverity.name(): MILD / MODERATE / SEVERE / NORMAL / PENDING_CHECK. */
    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    /** Where the triggering glucose came from: TRIAGE / MANUAL_VITALS / IOT_STREAM / REPEAT. */
    @Column(name = "glucose_source", length = 30)
    private String glucoseSource;

    /** True when classified under neonatal (< 28 days) thresholds. */
    @Column(name = "is_neonatal", nullable = false)
    @Builder.Default
    private boolean neonatal = false;

    @Column(name = "detected_by_name")
    private String detectedByName;

    /** When the next glucose recheck is due (detection/treatment + 15 min) — drives the recheck monitor. */
    @Column(name = "recheck_due_at")
    private Instant recheckDueAt;

    @Column(name = "treatment_given", columnDefinition = "TEXT")
    private String treatmentGiven;

    @Column(name = "treatment_given_at")
    private Instant treatmentGivenAt;

    @Column(name = "treatment_given_by_name")
    private String treatmentGivenByName;

    /** Follow-up glucose level after treatment */
    @Column(name = "repeat_glucose_level")
    private Double repeatGlucoseLevel;

    @Column(name = "repeat_glucose_at")
    private Instant repeatGlucoseAt;

    @Column(name = "resolved", nullable = false)
    @Builder.Default
    private boolean resolved = false;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_name")
    private String resolvedByName;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
