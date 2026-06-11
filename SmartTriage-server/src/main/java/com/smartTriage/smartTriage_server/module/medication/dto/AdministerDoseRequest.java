package com.smartTriage.smartTriage_server.module.medication.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Nurse confirms administration of a DUE dose (V67).
 *
 * <p>The dose verification step: the nurse states what they are about
 * to give; a mismatch against the order's structured dose is rejected
 * unless explicitly overridden with justification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdministerDoseRequest {

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

    /** Override a verification/recheck block — justification mandatory. */
    private Boolean override;

    @Size(max = 2000)
    private String overrideJustification;
}
