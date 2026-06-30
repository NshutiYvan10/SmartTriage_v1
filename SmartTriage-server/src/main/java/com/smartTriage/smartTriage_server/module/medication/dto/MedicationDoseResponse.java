package com.smartTriage.smartTriage_server.module.medication.dto;

import com.smartTriage.smartTriage_server.common.enums.DoseKind;
import com.smartTriage.smartTriage_server.common.enums.DoseStatus;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.MedicationPriority;
import com.smartTriage.smartTriage_server.common.enums.MedicationProductType;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One dose event (V67) — carries enough denormalised order / patient
 * context that the zone medication board can render a row without
 * extra fetches.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicationDoseResponse {

    private UUID id;
    private UUID medicationId;
    private UUID visitId;

    private DoseKind kind;
    private DoseStatus status;
    private Integer sequenceNumber;
    private Instant dueAt;

    private Instant givenAt;
    private UUID givenById;
    private String givenByName;
    private String witnessName;

    private BigDecimal doseValue;
    private String doseUnit;
    private Double rateValue;
    private String rateUnit;

    private String prnReason;
    private String gateEvaluation;

    private boolean isOverride;
    private String overrideJustification;
    private String statusReason;
    private int delayCount;

    // ── Denormalised order context (board display) ──
    private String drugName;
    private String orderDose;
    private MedicationRoute route;
    private MedicationPriority priority;
    private PrescriptionType prescriptionType;
    private MedicationProductType productType;
    private String productDetail;
    private boolean requiresWitness;
    private String prescribedByName;

    // ── Denormalised patient context (board display) ──
    private String patientName;
    private String visitNumber;
    private EdZone zone;
    private String bedLabel;

    private Instant createdAt;
}
