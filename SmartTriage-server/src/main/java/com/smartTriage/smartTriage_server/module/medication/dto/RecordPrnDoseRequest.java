package com.smartTriage.smartTriage_server.module.medication.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Nurse records a PRN administration (V67) — created at the moment of
 * giving, gated server-side by the order's minimum interval, max-per-
 * 24h cap, and structured vitals gate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordPrnDoseRequest {

    /** The condition that triggered this dose ("pain 6/10", "nausea"). */
    @NotBlank(message = "The PRN indication that triggered this dose is required")
    @Size(max = 255)
    private String prnReason;

    /** Display-name fallback when the caller carries no auth context. */
    @Size(max = 255)
    private String administeredByName;

    /** Verified dose actually given. Defaults to the order's dose. */
    @DecimalMin(value = "0.0", inclusive = false, message = "Dose must be positive")
    private BigDecimal doseValue;

    @Size(max = 20)
    private String doseUnit;

    /** Second clinician — mandatory when the order requires a witness. */
    @Size(max = 255)
    private String witnessName;

    @Size(max = 1000)
    private String notes;

    /** Override a failed gate (vitals / interval / cap) — justification mandatory. */
    private Boolean override;

    @Size(max = 2000)
    private String overrideJustification;
}
