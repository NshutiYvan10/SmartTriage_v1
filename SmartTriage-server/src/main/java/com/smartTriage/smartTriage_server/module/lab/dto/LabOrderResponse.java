package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
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

    private String specimenType;
    private Instant specimenCollectedAt;
    private String specimenCollectedByName;

    private Instant receivedByLabAt;
    private Instant processingStartedAt;
    private Instant resultedAt;

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

    private Integer turnaroundMinutes;
    private String notes;

    private Instant cancelledAt;
    private String cancelledByName;
    private String cancelReason;

    /** Current workflow status derived from timestamps */
    private String status;

    private Instant createdAt;
    private Instant updatedAt;
}
