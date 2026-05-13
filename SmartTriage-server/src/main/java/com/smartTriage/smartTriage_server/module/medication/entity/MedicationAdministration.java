package com.smartTriage.smartTriage_server.module.medication.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.MedicationPriority;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * MedicationAdministration — the structured medication log from the triage form.
 *
 * Both the Adult and Child Triage Forms include a medication table with columns:
 *   Time | Drug | Route | Prescribed By | Administered By | Time (Admin) | Countersigned By
 *
 * This entity captures EACH medication entry as a separate auditable record.
 * Multiple entries can exist per visit — this is a continuous medication
 * administration record (MAR) for the ED encounter.
 *
 * In real ED workflows, medications are prescribed and administered throughout
 * the visit. Each entry records the full chain of custody:
 *   1. Prescription: Who ordered it, what drug, what route, what dose
 *   2. Administration: Who gave it, when it was actually given
 *   3. Countersigning: Second clinician verification (safety check)
 */
@Entity
@Table(name = "medication_administrations", indexes = {
        @Index(name = "idx_med_admin_visit", columnList = "visit_id"),
        @Index(name = "idx_med_admin_prescribed_at", columnList = "prescribed_at"),
        @Index(name = "idx_med_admin_status", columnList = "status"),
        @Index(name = "idx_med_admin_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationAdministration extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    // ====================================================================
    // PRESCRIPTION
    // ====================================================================

    /** Drug name — free text as per form (e.g., "Paracetamol 500mg") */
    @Column(name = "drug_name", nullable = false, length = 255)
    private String drugName;

    /** Dose — free text (e.g., "500mg", "10mg/kg", "1g") */
    @Column(name = "dose", length = 100)
    private String dose;

    /** Route of administration (PO, IV, IM, etc.) */
    @Enumerated(EnumType.STRING)
    @Column(name = "route", nullable = false, length = 20)
    private MedicationRoute route;

    /** Frequency/schedule (e.g., "STAT", "Q6H", "PRN") */
    @Column(name = "frequency", length = 50)
    private String frequency;

    /**
     * Structured urgency tier — drives the nurse medication queue
     * sort, the STAT/URGENT SLA monitor, and the real-time push
     * prioritisation. Default ROUTINE for backward compatibility with
     * the legacy free-text {@code frequency} model: when this column
     * was added the existing rows had no priority signal and we
     * deliberately don't try to infer STAT from "STAT" appearing in
     * the frequency string — that's a UI hint, not a contract.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    @Builder.Default
    private MedicationPriority priority = MedicationPriority.ROUTINE;

    /** Time the medication was prescribed/ordered */
    @Column(name = "prescribed_at", nullable = false)
    private Instant prescribedAt;

    /** Prescribing clinician — from form column "Prescribed By" */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescribed_by_id")
    private User prescribedBy;

    /** Prescriber name (free text fallback if user not in system) */
    @Column(name = "prescribed_by_name", length = 255)
    private String prescribedByName;

    // ====================================================================
    // ADMINISTRATION
    // ====================================================================

    /** Time the medication was actually administered — from form column "Time (Admin)" */
    @Column(name = "administered_at")
    private Instant administeredAt;

    /** Administering clinician — from form column "Administered By" */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "administered_by_id")
    private User administeredBy;

    /** Administrator name (free text fallback) */
    @Column(name = "administered_by_name", length = 255)
    private String administeredByName;

    // ====================================================================
    // COUNTERSIGNING
    // ====================================================================

    /** Countersigning clinician — from form column "Countersigned By" */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "countersigned_by_id")
    private User countersignedBy;

    /** Countersigner name (free text fallback) */
    @Column(name = "countersigned_by_name", length = 255)
    private String countersignedByName;

    /** Time of countersigning */
    @Column(name = "countersigned_at")
    private Instant countersignedAt;

    // ====================================================================
    // STATUS & NOTES
    // ====================================================================

    /** Current status of this medication entry */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MedicationStatus status = MedicationStatus.PRESCRIBED;

    /** Clinical notes (e.g., reason for hold, adverse reaction) */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ====================================================================
    // ALLERGY OVERRIDE (V23)
    // ====================================================================
    // Set when the prescribe-time allergy cross-check (frontend
    // utils/allergyCheck.ts) found a conflict and the prescriber
    // explicitly acknowledged a hard-stop dialog. These fields are the
    // permanent audit record — the patient's allergy list can change
    // later but the snapshot here reflects what the prescriber saw.

    /** TRUE when prescribed against a known patient allergy. */
    @Column(name = "prescribed_despite_allergy", nullable = false)
    @Builder.Default
    private Boolean prescribedDespiteAllergy = Boolean.FALSE;

    /**
     * Snapshot of the conflicting allergens at decision time. Free-text;
     * format produced by the frontend formatAllergyMatches() helper:
     * "<token> [(<family>)]; …".
     */
    @Column(name = "allergy_override_matches", columnDefinition = "TEXT")
    private String allergyOverrideMatches;

    /** Server timestamp when the override dialog was confirmed. */
    @Column(name = "allergy_override_acknowledged_at")
    private Instant allergyOverrideAcknowledgedAt;

    // ====================================================================
    // INTERACTION OVERRIDE (V24)
    // ====================================================================
    // Set when the prescribe-time drug–drug interaction check
    // (frontend utils/interactionCheck.ts) found a conflict against
    // another active medication on the same visit and the prescriber
    // explicitly acknowledged the hard-stop dialog. Distinct from the
    // allergy fields because a single order can hit zero, one, or both.

    /** TRUE when prescribed despite a known drug–drug interaction. */
    @Column(name = "prescribed_despite_interaction", nullable = false)
    @Builder.Default
    private Boolean prescribedDespiteInteraction = Boolean.FALSE;

    /**
     * Snapshot of the conflicting interactions at decision time. Free-
     * text; format produced by the frontend formatInteractionMatches()
     * helper: "<other drug> + <prescribed class>/<other class>:
     * <mechanism> [<severity>]; …".
     */
    @Column(name = "interaction_override_matches", columnDefinition = "TEXT")
    private String interactionOverrideMatches;

    /** Server timestamp when the interaction override was confirmed. */
    @Column(name = "interaction_override_acknowledged_at")
    private Instant interactionOverrideAcknowledgedAt;
}
