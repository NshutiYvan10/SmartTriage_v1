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
    /**
     * Workflow 2 refinement — visit number + patient label hydrated
     * server-side so the doctor's aggregate "My Investigations" view
     * can render visit context without a second round-trip per row.
     * Null when the relationship isn't loaded (defensive — shouldn't
     * happen in practice).
     */
    private String visitNumber;
    private String patientName;
    private InvestigationType investigationType;
    private String testName;
    /** V62 — doctor User FK so the aggregate query can filter reliably. */
    private UUID orderedById;
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
