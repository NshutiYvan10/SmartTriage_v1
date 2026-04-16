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
        @Index(name = "idx_diagnosis_active", columnList = "is_active")
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
}
