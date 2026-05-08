package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.common.enums.CriticalContactMethod;
import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import com.smartTriage.smartTriage_server.common.enums.LabOrderStatus;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.common.enums.SpecimenRejectionReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
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

    private Instant createdAt;
    private Instant updatedAt;
}
