package com.smartTriage.smartTriage_server.module.medication.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.MedicationPriority;
import com.smartTriage.smartTriage_server.common.enums.MedicationProductType;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.common.enums.VitalGateComparator;
import com.smartTriage.smartTriage_server.common.enums.VitalGateParameter;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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

    // ====================================================================
    // TYPED ORDERS — Medication Management module (V67)
    // ====================================================================
    // This entity is the ORDER; individual administration events live in
    // MedicationDose (one order → many doses for SCHEDULED / PRN /
    // CONTINUOUS). prescriptionType is NULLABLE on purpose: legacy rows
    // and old API clients that don't send a type keep the exact pre-V67
    // single-shot behaviour (treated as ONE_TIME, legacy administer flow).

    /** Administration pattern. NULL = legacy single-shot row. */
    @Enumerated(EnumType.STRING)
    @Column(name = "prescription_type", length = 20)
    private PrescriptionType prescriptionType;

    /** What is administered. Blood products & fluids ride the same workflow. */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 20)
    @Builder.Default
    private MedicationProductType productType = MedicationProductType.DRUG;

    /** Free-text detail for non-drug products ("PRBC 2 units", "FFP 4 units"). */
    @Column(name = "product_detail", length = 255)
    private String productDetail;

    /** Structured dose value (legacy free-text {@code dose} stays for display). */
    @Column(name = "dose_value", precision = 10, scale = 3)
    private BigDecimal doseValue;

    /** Unit of {@link #doseValue} — mg, g, mcg, units, mL, … */
    @Column(name = "dose_unit", length = 20)
    private String doseUnit;

    // ── Schedule (SCHEDULED) ──

    /** When the first dose is due. NULL → prescribedAt. */
    @Column(name = "start_at")
    private Instant startAt;

    /** Interval between doses in hours (supports 0.5 = 30 min). */
    @Column(name = "interval_hours")
    private Double intervalHours;

    /** Hard end of the schedule; the order COMPLETEs when reached. */
    @Column(name = "end_at")
    private Instant endAt;

    /** Alternative stop condition: complete after N GIVEN doses. */
    @Column(name = "max_doses")
    private Integer maxDoses;

    // ── PRN controls ──

    /** Clinical indication that justifies a PRN dose ("pain", "nausea"). */
    @Column(name = "prn_indication", length = 255)
    private String prnIndication;

    /** Minimum hours between PRN doses (the "q6h" in "q6h PRN pain"). */
    @Column(name = "prn_min_interval_hours")
    private Double prnMinIntervalHours;

    /** Optional cap on PRN doses in any trailing 24-hour window. */
    @Column(name = "prn_max_doses_per_day")
    private Integer prnMaxDosesPerDay;

    // ── PRN vitals gate ("administer only if SBP ≥ 180") ──

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_parameter", length = 20)
    private VitalGateParameter gateParameter;

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_comparator", length = 3)
    private VitalGateComparator gateComparator;

    @Column(name = "gate_threshold")
    private Double gateThreshold;

    // ── Continuous infusion ──

    /** Prescribed rate ("100" in "100 mL/hr"). */
    @Column(name = "rate_value")
    private Double rateValue;

    /** Rate unit ("mL/hr", "units/hr", "mcg/kg/min"). */
    @Column(name = "rate_unit", length = 20)
    private String rateUnit;

    // ── High-alert approval gate ──

    /** TRUE when this order required charge-nurse approval at creation. */
    @Column(name = "approval_required", nullable = false)
    @Builder.Default
    private boolean approvalRequired = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @Column(name = "approved_by_name", length = 255)
    private String approvedByName;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_note", length = 500)
    private String approvalNote;

    /** TRUE when the prescriber skipped the approval gate as an emergency. */
    @Column(name = "emergency_override", nullable = false)
    @Builder.Default
    private boolean emergencyOverride = false;

    /** Mandatory justification when {@link #emergencyOverride} is true. */
    @Column(name = "emergency_justification", columnDefinition = "TEXT")
    private String emergencyJustification;

    /**
     * TRUE when administrations of this order need a bedside witness
     * (blood products always; formulary requiresDoubleCheck drugs).
     */
    @Column(name = "requires_witness", nullable = false)
    @Builder.Default
    private boolean requiresWitness = false;

    // ── Discontinue workflow ──

    @Column(name = "discontinued_at")
    private Instant discontinuedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discontinued_by_id")
    private User discontinuedBy;

    @Column(name = "discontinued_by_name", length = 255)
    private String discontinuedByName;

    @Column(name = "discontinue_reason", length = 500)
    private String discontinueReason;

    /** Set when the order reached its planned end (duration / max doses). */
    @Column(name = "completed_at")
    private Instant completedAt;

    // ── Modification chain (orders are replaced, never edited) ──

    /** Order this one replaced via the modify workflow. */
    @Column(name = "supersedes_id", columnDefinition = "uuid")
    private UUID supersedesId;

    /** Order that replaced this one. */
    @Column(name = "superseded_by_id", columnDefinition = "uuid")
    private UUID supersededById;

    /**
     * Effective administration pattern: legacy NULL-typed rows behave
     * as ONE_TIME everywhere the type is consulted.
     */
    @Transient
    public PrescriptionType effectiveType() {
        return prescriptionType != null ? prescriptionType : PrescriptionType.ONE_TIME;
    }

    /** First-dose anchor: explicit startAt, else the prescribe time. */
    @Transient
    public Instant effectiveStartAt() {
        return startAt != null ? startAt : prescribedAt;
    }
}
