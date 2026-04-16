package com.smartTriage.smartTriage_server.module.hypoglycemia.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording a repeat glucose check.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepeatGlucoseRequest {

    @NotNull(message = "Glucose level is required")
    @Positive(message = "Glucose level must be positive")
    private Double glucoseLevel;
}
