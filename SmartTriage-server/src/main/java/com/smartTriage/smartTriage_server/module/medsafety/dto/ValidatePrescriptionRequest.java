package com.smartTriage.smartTriage_server.module.medsafety.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to validate a medication prescription through the safety engine.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidatePrescriptionRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotNull(message = "Medication ID is required")
    private UUID medicationId;

    /** Patient weight in kg — required for pediatric weight-based dosing */
    private Double weightKg;

    /** Optional: explicit dose in mg to validate (overrides parsing from medication record) */
    private Double doseMg;
}
