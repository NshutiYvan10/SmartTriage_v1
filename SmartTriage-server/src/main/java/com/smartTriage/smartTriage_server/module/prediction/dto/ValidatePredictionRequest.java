package com.smartTriage.smartTriage_server.module.prediction.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidatePredictionRequest {

    @NotNull(message = "Actual value is required for validation")
    private Integer actualValue;
}
