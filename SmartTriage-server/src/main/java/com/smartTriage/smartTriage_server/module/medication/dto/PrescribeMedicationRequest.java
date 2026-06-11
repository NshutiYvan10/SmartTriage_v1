package com.smartTriage.smartTriage_server.module.medication.dto;

import com.smartTriage.smartTriage_server.common.enums.AllergySeverity;
import com.smartTriage.smartTriage_server.common.enums.MedicationPriority;
import com.smartTriage.smartTriage_server.common.enums.MedicationProductType;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.common.enums.VitalGateComparator;
import com.smartTriage.smartTriage_server.common.enums.VitalGateParameter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Request to prescribe/record a medication administration entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrescribeMedicationRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotBlank(message = "Drug name is required")
    private String drugName;

    private String dose;

    @NotNull(message = "Route is required")
    private MedicationRoute route;

    private String frequency;

    /**
     * Workflow 3 — structured urgency tier. Defaults to ROUTINE on
     * the backend when omitted so old clients keep working without
     * upgrade. STAT raises the order to the top of the nurse queue
     * and starts a 10-minute SLA timer.
     */
    private MedicationPriority priority;

    /** Optional: explicit prescriber name if not current user */
    private String prescribedByName;

    private String notes;

    // ────────── ALLERGY OVERRIDE (V23) ──────────
    // Populated by the frontend AllergyConfirmDialog when the prescriber
    // chose to prescribe despite a known allergy. Both fields are
    // nullable — for the common case (no conflict), the request body is
    // unchanged.

    /** TRUE when the prescriber acknowledged an allergy conflict. */
    private Boolean prescribedDespiteAllergy;

    /**
     * Free-text snapshot of the conflicts the dialog showed, e.g.
     * "penicillin (penicillins/beta-lactam); sulfa (sulfa drugs)".
     * Persisted verbatim so the audit record reflects exactly what the
     * prescriber saw at decision time.
     */
    private String allergyOverrideMatches;

    /**
     * Structured severity of the allergy that was overridden (V58 /
     * Workflow 2). Nullable for backward compatibility — when the
     * frontend hasn't been upgraded, the override alert falls back to
     * CRITICAL (the safest assumption). When present, the severity
     * scales the generated ClinicalAlert:
     *   ANAPHYLAXIS / SEVERE → CRITICAL
     *   MODERATE / UNKNOWN   → HIGH
     *   MILD                 → MEDIUM
     */
    private AllergySeverity allergyOverrideSeverity;

    // ────────── INTERACTION OVERRIDE (V24) ──────────
    // Populated by the same PrescribeSafetyDialog when an interaction
    // conflict was acknowledged. Both nullable for the common no-
    // conflict case.

    /** TRUE when the prescriber acknowledged a drug–drug interaction. */
    private Boolean prescribedDespiteInteraction;

    /**
     * Free-text snapshot of the interactions the dialog showed, e.g.
     * "Warfarin 5mg + aspirin/warfarin: additive bleeding risk [major]".
     */
    private String interactionOverrideMatches;

    // ────────── TYPED ORDERS (V67 — Medication Management) ──────────
    // All nullable: an old client that sends none of these gets the
    // exact pre-V67 single-shot behaviour.

    /** ONE_TIME | SCHEDULED | PRN | CONTINUOUS. Null = legacy flow. */
    private PrescriptionType prescriptionType;

    /** DRUG (default) | BLOOD_PRODUCT | IV_FLUID | OTHER. */
    private MedicationProductType productType;

    /** Detail for non-drug products ("PRBC 2 units", "FFP 4 units"). */
    @Size(max = 255)
    private String productDetail;

    /** Structured dose value — drives administration-time verification. */
    @DecimalMin(value = "0.0", inclusive = false, message = "Dose must be positive")
    private BigDecimal doseValue;

    @Size(max = 20)
    private String doseUnit;

    /** When the first dose is due (SCHEDULED/ONE_TIME). Null → now. */
    private Instant startAt;

    /** Hours between scheduled doses (0.5 = 30 min). */
    @DecimalMin(value = "0.0", inclusive = false, message = "Interval must be positive")
    private Double intervalHours;

    /** Schedule hard stop. */
    private Instant endAt;

    /** Alternative stop: complete after N given doses. */
    @Min(value = 1, message = "Max doses must be at least 1")
    private Integer maxDoses;

    /** PRN: indication ("pain", "nausea ≥ moderate"). */
    @Size(max = 255)
    private String prnIndication;

    /** PRN: minimum hours between doses. */
    @DecimalMin(value = "0.0", inclusive = false, message = "PRN interval must be positive")
    private Double prnMinIntervalHours;

    /** PRN: cap per trailing 24 h. */
    @Min(value = 1, message = "PRN daily cap must be at least 1")
    private Integer prnMaxDosesPerDay;

    /** PRN vitals gate: parameter / comparator / threshold trio. */
    private VitalGateParameter gateParameter;
    private VitalGateComparator gateComparator;
    private Double gateThreshold;

    /** CONTINUOUS: prescribed rate. */
    @DecimalMin(value = "0.0", inclusive = false, message = "Rate must be positive")
    private Double rateValue;

    @Size(max = 20)
    private String rateUnit;

    /**
     * Emergency override of the high-alert approval gate. Requires
     * {@link #emergencyJustification}; logged + department-visible
     * alert by design.
     */
    private Boolean emergencyOverride;

    @Size(max = 2000)
    private String emergencyJustification;
}
