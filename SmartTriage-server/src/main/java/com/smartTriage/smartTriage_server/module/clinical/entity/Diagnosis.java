package com.smartTriage.smartTriage_server.module.clinical.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.DiagnosisType;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Diagnosis — records provisional, confirmed, differential, and working diagnoses
 * for a patient visit.
 *
 * The Rwanda triage forms include a "Diagnosis" section that captures:
 *   - Provisional diagnosis at triage
 *   - Updated diagnoses during the visit
 *   - Final confirmed diagnosis at disposition
 *
 * Multiple diagnoses can exist per visit (differential diagnosis list).
 */
@Entity
@Table(name = "diagnoses", indexes = {
        @Index(name = "idx_diagnosis_visit", columnList = "visit_id"),
        @Index(name = "idx_diagnosis_type", columnList = "diagnosis_type"),
        @Index(name = "idx_diagnosis_active", columnList = "is_active"),
        @Index(name = "idx_diagnosis_original", columnList = "original_diagnosis_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Diagnosis extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    /** Type: PROVISIONAL, CONFIRMED, DIFFERENTIAL, WORKING */
    @Enumerated(EnumType.STRING)
    @Column(name = "diagnosis_type", nullable = false, length = 20)
    private DiagnosisType diagnosisType;

    /** ICD-10 code (optional but recommended) */
    @Column(name = "icd_code", length = 20)
    private String icdCode;

    /** Description of the diagnosis — free text */
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    /** Name of the clinician who made the diagnosis */
    @Column(name = "diagnosed_by_name", length = 255)
    private String diagnosedByName;

    /** Time the diagnosis was made */
    @Column(name = "diagnosed_at", nullable = false)
    private Instant diagnosedAt;

    /** Whether this is the primary/principal diagnosis */
    @Column(name = "is_primary")
    @Builder.Default
    private Boolean isPrimary = false;

    /** Clinical notes about this diagnosis */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ── Edit-with-history (non-destructive amendment) ──
    // Editing a diagnosis creates a NEW row linked back to the first version via
    // originalDiagnosis; the superseded row is soft-deleted so current-state reads
    // show only the latest, while the full chain stays retrievable for history.

    /** The first version this row amends (points at the root original). Null on originals. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_diagnosis_id")
    private Diagnosis originalDiagnosis;

    /** True when this row was produced by editing an earlier diagnosis. */
    @Column(name = "is_amendment", nullable = false)
    @Builder.Default
    private boolean isAmendment = false;

    /** Why the diagnosis was changed (required when amending). */
    @Column(name = "amendment_reason", columnDefinition = "TEXT")
    private String amendmentReason;

    /** When this amendment was made. */
    @Column(name = "amended_at")
    private Instant amendedAt;
}
