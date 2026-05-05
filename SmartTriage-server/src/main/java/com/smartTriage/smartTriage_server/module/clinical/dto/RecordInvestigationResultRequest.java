package com.smartTriage.smartTriage_server.module.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to record investigation results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordInvestigationResultRequest {

    @NotNull(message = "Investigation ID is required")
    private UUID investigationId;

    @NotBlank(message = "Result is required")
    private String result;

    /**
     * Phase 12b — optional numeric value extracted from the result
     * (e.g. 1.8 for "Cr 1.8 mg/dL"). When present together with
     * resultUnit, drives downstream calculators like Cockcroft-Gault
     * eGFR. Both fields are optional — qualitative results (e.g.
     * urine dipstick "trace blood") still record only the free text.
     */
    private Double resultNumeric;
    private String resultUnit;

    /** Whether the result is abnormal */
    private Boolean isAbnormal;

    /** Whether this is a critical value requiring immediate action */
    private Boolean isCritical;

    private String notes;
}
