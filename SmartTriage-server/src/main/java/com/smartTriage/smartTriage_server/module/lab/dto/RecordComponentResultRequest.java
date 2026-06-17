package com.smartTriage.smartTriage_server.module.lab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One analyte's entered value within a multi-analyte (panel) result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordComponentResultRequest {

    @NotBlank(message = "Analyte name is required")
    private String analyteName;

    private String analyteCode;

    /** Raw entered value (text carries non-numeric results e.g. "Positive"). */
    private String resultValue;

    /** Parsed numeric value (if applicable) for per-analyte critical evaluation. */
    private Double resultNumeric;

    private String resultUnit;
}
