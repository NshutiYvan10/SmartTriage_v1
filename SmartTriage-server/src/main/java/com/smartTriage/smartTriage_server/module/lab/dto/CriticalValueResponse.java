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
 * Response DTO for an unacknowledged critical lab result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CriticalValueResponse {

    private UUID labOrderId;
    private UUID visitId;
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
