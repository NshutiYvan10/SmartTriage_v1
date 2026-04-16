package com.smartTriage.smartTriage_server.module.clinical.dto;

import com.smartTriage.smartTriage_server.common.enums.DiagnosisType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to create a diagnosis for a visit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDiagnosisRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotNull(message = "Diagnosis type is required")
    private DiagnosisType diagnosisType;

    /** ICD-10 code (optional but recommended) */
    private String icdCode;

    @NotBlank(message = "Diagnosis description is required")
    private String description;

    /** Name of diagnosing clinician */
    private String diagnosedByName;

    /** Whether this is the primary/principal diagnosis */
    private Boolean isPrimary;

    private String notes;
}
