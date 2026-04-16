package com.smartTriage.smartTriage_server.module.medsafety.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * MedicationSafetyCheck — records the result of a medication safety validation.
 *
 * Every prescription passes through the MedicationSafetyEngine, which checks:
 *   1. Allergy cross-reactivity against patient's known allergies
 *   2. Dose range validation (adult/pediatric weight-based)
 *   3. Drug-drug interaction screening against active medications
 *   4. Duplicate therapy detection within the same drug class
 *
 * Results are persisted for audit, allowing clinicians to override with documented
 * reasons. Critical safety failures (allergy match, severe overdose) BLOCK
 * administration until a clinician explicitly overrides.
 */
@Entity
@Table(name = "medication_safety_checks", indexes = {
        @Index(name = "idx_med_safety_visit", columnList = "visit_id"),
        @Index(name = "idx_med_safety_medication", columnList = "medication_id"),
        @Index(name = "idx_med_safety_overall", columnList = "overall_safe"),
        @Index(name = "idx_med_safety_checked_at", columnList = "checked_at"),
        @Index(name = "idx_med_safety_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationSafetyCheck extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medication_id", nullable = false)
    private MedicationAdministration medication;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "drug_name", nullable = false, length = 255)
    private String drugName;

    @Column(name = "prescribed_dose_mg")
    private Double prescribedDoseMg;

    @Column(name = "patient_weight_kg")
    private Double patientWeightKg;

    // ====================================================================
    // CHECK RESULTS
    // ====================================================================

    @Column(name = "allergy_check_passed", nullable = false)
    @Builder.Default
    private boolean allergyCheckPassed = true;

    @Column(name = "allergy_warning", columnDefinition = "TEXT")
    private String allergyWarning;

    @Column(name = "dose_check_passed", nullable = false)
    @Builder.Default
    private boolean doseCheckPassed = true;

    @Column(name = "dose_warning", columnDefinition = "TEXT")
    private String doseWarning;

    @Column(name = "interaction_check_passed", nullable = false)
    @Builder.Default
    private boolean interactionCheckPassed = true;

    @Column(name = "interaction_warning", columnDefinition = "TEXT")
    private String interactionWarning;

    @Column(name = "duplicate_therapy_check_passed", nullable = false)
    @Builder.Default
    private boolean duplicateTherapyCheckPassed = true;

    @Column(name = "duplicate_warning", columnDefinition = "TEXT")
    private String duplicateWarning;

    @Column(name = "overall_safe", nullable = false)
    @Builder.Default
    private boolean overallSafe = true;

    // ====================================================================
    // OVERRIDE
    // ====================================================================

    /** Name of clinician who overrode the safety check */
    @Column(name = "overridden_by", length = 255)
    private String overriddenBy;

    /** Documented reason for the override */
    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    /** Timestamp of the override */
    @Column(name = "overridden_at")
    private Instant overriddenAt;
}
