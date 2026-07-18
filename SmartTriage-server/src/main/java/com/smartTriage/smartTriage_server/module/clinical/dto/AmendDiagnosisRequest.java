package com.smartTriage.smartTriage_server.module.clinical.dto;

import com.smartTriage.smartTriage_server.common.enums.DiagnosisType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to amend (edit) an existing diagnosis without losing the original.
 *
 * <p>The amendment creates a new linked diagnosis version; the prior version is
 * preserved and remains retrievable via the history endpoint. A reason is
 * mandatory — this is a change to a clinical record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmendDiagnosisRequest {

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

    @NotBlank(message = "A reason for the change is required")
    private String amendmentReason;
}
