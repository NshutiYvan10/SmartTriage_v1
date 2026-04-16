package com.smartTriage.smartTriage_server.module.safety.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording root cause analysis results.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RootCauseRequest {

    @NotBlank(message = "Root cause analysis is required")
    private String rootCauseAnalysis;

    @NotBlank(message = "Root cause category is required")
    private String rootCauseCategory;
}
