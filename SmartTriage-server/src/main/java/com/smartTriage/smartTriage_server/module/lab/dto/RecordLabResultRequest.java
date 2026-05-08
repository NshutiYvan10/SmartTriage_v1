package com.smartTriage.smartTriage_server.module.lab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to record a lab test result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordLabResultRequest {

    @NotBlank(message = "Result value is required")
    private String resultValue;

    /** Unit of measurement — e.g., "mmol/L", "g/dL", "cells/uL" */
    private String resultUnit;

    /** Parsed numeric value (if applicable) for critical value evaluation */
    private Double resultNumeric;

    private Double referenceRangeMin;
    private Double referenceRangeMax;

    /** Tech who entered (and self-verified) the result. */
    private String enteredByName;

    /** True if the tech flagged the specimen as quality-suspect at result time. */
    private boolean specimenQualityConcern;

    private String notes;
}
