package com.smartTriage.smartTriage_server.module.patient.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.AllergySeverity;
import com.smartTriage.smartTriage_server.common.enums.AllergyVerificationStatus;
import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * PatientAllergy — structured replacement for the legacy free-text
 * {@code Patient.knownAllergies} column.
 *
 * <p>The legacy column stays on the Patient row as a fallback for
 * un-migrated records, but every new allergy entry lives here so the
 * {@code MedicationSafetyEngine} can:
 *
 * <ul>
 *   <li>match by FK to {@link DrugFormulary} (no more typo misses),</li>
 *   <li>scale the dialog flavour by {@link AllergySeverity},</li>
 *   <li>show the prescriber what the reaction was so they can
 *       decide whether the override is clinically reasonable,</li>
 *   <li>track verification status so a refuted allergy stops
 *       firing safety alerts without losing the audit trail.</li>
 * </ul>
 *
 * <p>The allergen is captured two ways: a nullable FK to the
 * formulary entry (when picked from the catalog) AND a free-text
 * {@code allergenName} (always populated — preserves display and
 * supports non-drug allergens like shellfish or latex). The engine
 * prefers the FK when both are present.
 */
@Entity
@Table(name = "patient_allergies", indexes = {
        @Index(name = "idx_patient_allergy_patient", columnList = "patient_id"),
        @Index(name = "idx_patient_allergy_allergen_name", columnList = "allergen_name"),
        @Index(name = "idx_patient_allergy_formulary", columnList = "allergen_formulary_id"),
        @Index(name = "idx_patient_allergy_active", columnList = "is_active"),
        @Index(name = "idx_patient_allergy_verification", columnList = "verification_status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientAllergy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /**
     * Optional FK to the formulary entry. Set when the allergen was
     * picked from the searchable drug catalog. NULL when the allergen
     * is a free-text entry (non-drug or not yet in the catalog).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allergen_formulary_id")
    private DrugFormulary allergenFormulary;

    /**
     * Always populated — the display name the clinician sees, and
     * the fallback the safety engine substring-matches when there's
     * no formulary FK. Lower-cased on save for case-insensitive
     * matching downstream.
     */
    @Column(name = "allergen_name", nullable = false, length = 200)
    private String allergenName;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private AllergySeverity severity;

    /**
     * Free-text reaction description shown to the prescriber on the
     * override dialog so they know what happened to the patient last
     * time. Example: "facial swelling and difficulty breathing".
     */
    @Column(name = "reaction", length = 500)
    private String reaction;

    /**
     * When the patient last reacted to this allergen. Nullable —
     * patients often don't remember the exact date.
     */
    @Column(name = "onset_date")
    private LocalDate onsetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    @Builder.Default
    private AllergyVerificationStatus verificationStatus = AllergyVerificationStatus.PATIENT_REPORTED;

    /** Who recorded the allergy (display name — separate from the audit user). */
    @Column(name = "recorded_by_name", length = 200)
    private String recordedByName;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    /**
     * If the allergy is refuted, capture who refuted it and why.
     * Together with verificationStatus = REFUTED, this turns the
     * row into an audit fact: "Dr X reviewed this allergy on date Y
     * and determined it was an intolerance, not a true allergy."
     */
    @Column(name = "refuted_by_name", length = 200)
    private String refutedByName;

    @Column(name = "refuted_at")
    private Instant refutedAt;

    @Column(name = "refute_reason", length = 500)
    private String refuteReason;

    @PrePersist
    public void onCreate() {
        if (recordedAt == null) {
            recordedAt = Instant.now();
        }
        if (allergenName != null) {
            allergenName = allergenName.trim();
        }
    }
}
