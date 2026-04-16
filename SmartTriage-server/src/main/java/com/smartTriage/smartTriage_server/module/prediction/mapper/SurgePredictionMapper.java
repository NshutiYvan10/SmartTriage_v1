package com.smartTriage.smartTriage_server.module.prediction.mapper;

import com.smartTriage.smartTriage_server.module.prediction.dto.SurgePredictionResponse;
import com.smartTriage.smartTriage_server.module.prediction.entity.SurgePrediction;

public final class SurgePredictionMapper {

    private SurgePredictionMapper() {
    }

    public static SurgePredictionResponse toResponse(SurgePrediction prediction) {
        SurgePredictionResponse.SurgePredictionResponseBuilder builder =
                SurgePredictionResponse.builder()
                        .id(prediction.getId())
                        .predictedAt(prediction.getPredictedAt())
                        .predictionHorizonHours(prediction.getPredictionHorizonHours())
                        .predictedEdAdmissions(prediction.getPredictedEdAdmissions())
                        .predictedIcuDemand(prediction.getPredictedIcuDemand())
                        .predictedRedPatients(prediction.getPredictedRedPatients())
                        .currentEdOccupancy(prediction.getCurrentEdOccupancy())
                        .currentIcuOccupancy(prediction.getCurrentIcuOccupancy())
                        .edCapacity(prediction.getEdCapacity())
                        .icuCapacity(prediction.getIcuCapacity())
                        .surgeRiskScore(prediction.getSurgeRiskScore())
                        .surgeRiskLevel(prediction.getSurgeRiskLevel())
                        .historicalAvgForPeriod(prediction.getHistoricalAvgForPeriod())
                        .currentArrivalRate(prediction.getCurrentArrivalRate())
                        .trendDirection(prediction.getTrendDirection())
                        .seasonalFactor(prediction.getSeasonalFactor())
                        .notes(prediction.getNotes())
                        .wasAccurate(prediction.getWasAccurate())
                        .actualValue(prediction.getActualValue())
                        .createdAt(prediction.getCreatedAt());

        if (prediction.getHospital() != null) {
            builder.hospitalId(prediction.getHospital().getId());
            builder.hospitalName(prediction.getHospital().getName());
        }

        return builder.build();
    }
}
