package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.common.enums.CriticalContactMethod;
import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.LabOrderStatus;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.common.enums.SpecimenRejectionReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for a lab order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabOrderResponse {

    private UUID id;
    private UUID visitId;

    // ── Denormalised patient context (lab worklist card display) ──
    // Every lab worklist card MUST show WHO the order is for and WHERE
    // that patient is, without a second fetch. Populated from
    // order.visit → patient / currentEdZone / currentBed.
    private UUID patientId;
    private String patientName;
    private String visitNumber;
    private EdZone currentZone;
    private String currentBedLabel;

    private UUID investigationId;
    private String orderNumber;
    private String testName;
    private String testCode;
    private LabPriority priority;

    private Instant orderedAt;
    private String orderedByName;
    private String clinicalIndication;

    private String specimenType;
    private Instant specimenCollectedAt;
    private String specimenCollectedByName;

    private Instant acknowledgedByLabAt;
    private String acknowledgedByLabName;

    private Instant receivedByLabAt;
    private String accessionNumber;
    private Instant processingStartedAt;
    private Instant resultedAt;
    private String enteredByName;
    private Instant verifiedAt;
    private String verifiedByName;
    private boolean verificationRequired;
    private Instant verificationTimeoutAt;
    private boolean verificationAutoReleased;
    private boolean verificationOverride;
    private String verificationOverrideReason;
    private String verificationOverrideByName;
    private Instant verificationOverrideAt;
    private int verificationRejectionCount;
    private String verificationRejectionReason;
    private String verificationRejectedByName;
    private Instant verificationRejectedAt;

    private String resultValue;
    private String resultUnit;
    private Double resultNumeric;
    private Double referenceRangeMin;
    private Double referenceRangeMax;

    private boolean isAbnormal;
    private boolean isCritical;
    private CriticalValueType criticalValueType;
    private Instant criticalValueNotifiedAt;
    private String criticalValueNotifiedTo;
    private Instant criticalValueAcknowledgedAt;
    private String criticalReadbackText;
    private CriticalContactMethod criticalContactMethod;

    private Integer turnaroundMinutes;
    private String notes;

    private Instant cancelledAt;
    private String cancelledByName;
    private String cancelReason;

    private Instant rejectedAt;
    private String rejectedByName;
    private SpecimenRejectionReason rejectionReason;
    private String rejectionNotes;

    /** Authoritative workflow status (post V48). */
    private LabOrderStatus status;

    /** Per-analyte results for a multi-analyte (panel) order; empty for single-analyte tests. */
    private List<LabResultComponentResponse> components;

    private Instant createdAt;
    private Instant updatedAt;
}
