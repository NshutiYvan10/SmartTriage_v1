package com.smartTriage.smartTriage_server.module.clinical.dto;

import com.smartTriage.smartTriage_server.common.enums.InvestigationStatus;
import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for an investigation record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestigationResponse {

    private UUID id;
    private UUID visitId;
    private InvestigationType investigationType;
    private String testName;
    private String orderedByName;
    private Instant orderedAt;
    private Instant specimenCollectedAt;
    private Instant resultedAt;
    private String result;
    /**
     * Phase 12b — principal scalar value of the result. Optional
     * (null when the result is purely qualitative). Paired with
     * resultUnit. Drives Cockcroft-Gault eGFR for renal-risk
     * dose checking.
     */
    private Double resultNumeric;
    private String resultUnit;
    private Boolean isAbnormal;
    private Boolean isCritical;
    private InvestigationStatus status;
    private String priority;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
