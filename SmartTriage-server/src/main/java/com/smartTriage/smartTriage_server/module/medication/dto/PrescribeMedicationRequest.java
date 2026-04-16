package com.smartTriage.smartTriage_server.module.medication.dto;

import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to prescribe/record a medication administration entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrescribeMedicationRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotBlank(message = "Drug name is required")
    private String drugName;

    private String dose;

    @NotNull(message = "Route is required")
    private MedicationRoute route;

    private String frequency;

    /** Optional: explicit prescriber name if not current user */
    private String prescribedByName;

    private String notes;
}
