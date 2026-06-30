package com.smartTriage.smartTriage_server.module.medication.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.MedicationPriority;
import com.smartTriage.smartTriage_server.common.enums.MedicationProductType;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.common.enums.VitalGateComparator;
import com.smartTriage.smartTriage_server.common.enums.VitalGateParameter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Full response DTO for a Medication Administration Record (MAR) entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicationResponse {

    private UUID id;
    private UUID visitId;

    // ── Denormalised patient context (board / queue display) ──
    // Every patient-scoped medication row MUST be able to show WHO the
    // order is for and WHERE that patient is, without a second fetch.
    // Populated from visit → patient / currentEdZone / currentBed.
    private UUID patientId;
    private String patientName;
    private String visitNumber;
    private EdZone zone;
    private String bedLabel;

    // Drug details
    private String drugName;
    private String dose;
    private MedicationRoute route;
    private String frequency;

    /** Workflow 3 — STAT / URGENT / ROUTINE. Drives nurse queue
     *  sort, SLA monitor, and visual treatment. */
    private MedicationPriority priority;
    /** Pre-rendered display label e.g. "STAT". */
    private String priorityLabel;

    // Prescribing chain
    private UUID prescribedById;
    private String prescribedByName;
    private Instant prescribedAt;

    // Administration chain
    private UUID administeredById;
    private String administeredByName;
    private Instant administeredAt;

    // Countersigning chain
    private UUID countersignedById;
    private String countersignedByName;
    private Instant countersignedAt;

    // Status & notes
    private MedicationStatus status;
    private String notes;

    // Allergy override (V23) — exposed so the frontend can render a
    // visible badge on overridden orders. Other clinicians coming into
    // the case need to see at a glance "this drug was prescribed
    // against a known allergy."
    private Boolean prescribedDespiteAllergy;
    private String allergyOverrideMatches;
    private Instant allergyOverrideAcknowledgedAt;

    // Interaction override (V24) — same rationale as the allergy
    // override fields, but for drug–drug interaction conflicts. A
    // single order can carry both flags.
    private Boolean prescribedDespiteInteraction;
    private String interactionOverrideMatches;
    private Instant interactionOverrideAcknowledgedAt;

    // ── Typed orders (V67 — Medication Management) ──
    /** Null = legacy single-shot row (treated as ONE_TIME). */
    private PrescriptionType prescriptionType;
    private MedicationProductType productType;
    private String productDetail;
    private BigDecimal doseValue;
    private String doseUnit;
    private Instant startAt;
    private Double intervalHours;
    private Instant endAt;
    private Integer maxDoses;
    private String prnIndication;
    private Double prnMinIntervalHours;
    private Integer prnMaxDosesPerDay;
    private VitalGateParameter gateParameter;
    private VitalGateComparator gateComparator;
    private Double gateThreshold;
    private Double rateValue;
    private String rateUnit;
    private boolean approvalRequired;
    private String approvedByName;
    private Instant approvedAt;
    private String approvalNote;
    private boolean emergencyOverride;
    private String emergencyJustification;
    private boolean requiresWitness;
    private Instant discontinuedAt;
    private String discontinuedByName;
    private String discontinueReason;
    private Instant completedAt;
    private UUID supersedesId;
    private UUID supersededById;

    /** Doses GIVEN so far (service-enriched; null when not computed). */
    private Long givenDoseCount;
    /** Next open DUE time, if any (service-enriched). */
    private Instant nextDueAt;

    // Audit
    private Instant createdAt;
    private Instant updatedAt;
}
