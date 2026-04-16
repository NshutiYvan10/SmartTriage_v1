package com.smartTriage.smartTriage_server.module.prediction.dto;

import com.smartTriage.smartTriage_server.common.enums.SurgeRiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SurgePredictionResponse {

    private UUID id;
    private UUID hospitalId;
    private String hospitalName;
    private Instant predictedAt;
    private Integer predictionHorizonHours;

    // Predictions
    private Integer predictedEdAdmissions;
    private Integer predictedIcuDemand;
    private Integer predictedRedPatients;
    private Integer currentEdOccupancy;
    private Integer currentIcuOccupancy;
    private Integer edCapacity;
    private Integer icuCapacity;
    private Double surgeRiskScore;
    private SurgeRiskLevel surgeRiskLevel;

    // Basis
    private Double historicalAvgForPeriod;
    private Double currentArrivalRate;
    private String trendDirection;
    private Double seasonalFactor;
    private String notes;

    // Validation
    private Boolean wasAccurate;
    private Integer actualValue;

    private Instant createdAt;
}
