package com.smartTriage.smartTriage_server.module.prediction.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneratePredictionRequest {

    @Min(value = 1, message = "Horizon must be at least 1 hour")
    @Max(value = 48, message = "Horizon cannot exceed 48 hours")
    @Builder.Default
    private int horizonHours = 4;
}
