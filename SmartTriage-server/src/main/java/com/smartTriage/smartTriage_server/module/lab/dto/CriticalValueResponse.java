package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for an unacknowledged critical lab result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CriticalValueResponse {

    private UUID labOrderId;
    private UUID visitId;

    // ── Denormalised patient context (critical-result banner / tab) ──
    // A critical lab result MUST name WHO it is for and WHERE that
    // patient is so the doctor can act without a second fetch.
    // Populated from order.visit → patient / currentEdZone / currentBed.
    private UUID patientId;
    private String patientName;
    private String visitNumber;
    private EdZone currentZone;
    private String currentBedLabel;

    private String orderNumber;
    private String testName;
    private LabPriority priority;

    private String resultValue;
    private String resultUnit;
    private Double resultNumeric;

    private CriticalValueType criticalValueType;
    private String criticalDescription;

    private Instant resultedAt;
    private Instant criticalValueNotifiedAt;
    private String criticalValueNotifiedTo;

    /** Minutes since result was reported — for escalation tracking */
    private long minutesSinceResult;
}
