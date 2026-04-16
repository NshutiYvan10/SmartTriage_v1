package com.smartTriage.smartTriage_server.module.hypoglycemia.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording hypoglycemia treatment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordTreatmentRequest {

    @NotBlank(message = "Treatment description is required")
    private String treatment;

    private String treatedByName;
}
