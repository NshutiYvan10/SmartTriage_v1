package com.smartTriage.smartTriage_server.module.patient.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.ChronicConditionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * PatientChronicCondition — structured replacement for the legacy
 * free-text {@code Patient.chronicConditions} column.
 *
 * <p>Mirrors {@link PatientAllergy}: the legacy column stays as a
 * fallback for un-migrated records, and the safety engine prefers
 * structured rows over free-text parsing. Differences from the
 * allergy entity:
 *
 * <ul>
 *   <li>No severity — conditions are graded by status (ACTIVE /
 *       CONTROLLED / IN_REMISSION / RESOLVED) which captures
 *       clinical relevance better than severity for chronic
 *       diseases.</li>
 *   <li>Optional {@code conditionCode} field carries a curated
 *       short-code from the frontend catalog (HTN, T2DM, COPD,
 *       SCD, etc.) when the clinician picked from the catalog.
 *       Free-text {@code conditionName} is always populated.</li>
 *   <li>{@code RESOLVED} on the status column replaces the
 *       "REFUTED" idea from allergies — same audit semantic.</li>
 * </ul>
 *
 * <p>Consumers (frontend safety checks) read structured rows where
 * {@code status} is ACTIVE or CONTROLLED. IN_REMISSION / RESOLVED
 * rows stay on the chart for history but don't drive safety gates.
 */
@Entity
@Table(name = "patient_chronic_conditions", indexes = {
        @Index(name = "idx_patient_chronic_patient", columnList = "patient_id"),
        @Index(name = "idx_patient_chronic_code", columnList = "condition_code"),
        @Index(name = "idx_patient_chronic_status", columnList = "status"),
        @Index(name = "idx_patient_chronic_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientChronicCondition extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /**
     * Curated short code from the frontend catalog (e.g. HTN, T2DM,
     * COPD, SCD, CKD). Optional — null for free-text entries that
     * didn't match the catalog. Indexed for the safety engine's
     * "does this patient have CKD?" lookup so it doesn't have to
     * substring-search {@link #conditionName}.
     */
    @Column(name = "condition_code", length = 40)
    private String conditionCode;

    /**
     * Display name. Always populated. When the clinician picked
     * from the catalog, this is the canonical label
     * ("Hypertension"). When free-text, it's the clinician's own
     * text.
     */
    @Column(name = "condition_name", nullable = false, length = 200)
    private String conditionName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ChronicConditionStatus status = ChronicConditionStatus.ACTIVE;

    /**
     * Free-text clinical details. Examples:
     *   • CKD: "Stage 3b, eGFR 35, on losartan"
     *   • T2DM: "Diagnosed 2018, on metformin + glipizide"
     *   • HIV: "On TLD since 2021, VL undetectable Mar 2026"
     * Surfaced to the prescriber on the chart so they can adjust
     * doses without having to dig further.
     */
    @Column(name = "notes", length = 500)
    private String notes;

    /** When the condition was first diagnosed. Nullable — patients often don't remember exactly. */
    @Column(name = "onset_date")
    private LocalDate onsetDate;

    /** Display name of the clinician who recorded the condition. */
    @Column(name = "recorded_by_name", length = 200)
    private String recordedByName;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    /**
     * If the condition is marked RESOLVED, capture who resolved it
     * and the reason. Same audit pattern as the allergy refute path
     * so the next clinician understands why the safety engine no
     * longer gates on this condition.
     */
    @Column(name = "resolved_by_name", length = 200)
    private String resolvedByName;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolve_reason", length = 500)
    private String resolveReason;

    @PrePersist
    public void onCreate() {
        if (recordedAt == null) {
            recordedAt = Instant.now();
        }
        if (conditionName != null) {
            conditionName = conditionName.trim();
        }
        if (conditionCode != null) {
            conditionCode = conditionCode.trim().toUpperCase();
            if (conditionCode.isEmpty()) {
                conditionCode = null;
            }
        }
    }
}
