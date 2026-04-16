package com.smartTriage.smartTriage_server.module.prediction.dto;

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
public class IcuDemandPrediction {

    private UUID hospitalId;
    private Instant predictedAt;
    private int horizonHours;

    private int currentIcuPatients;
    private int icuCapacity;
    private int hemodynamicallyUnstablePatients;
    private int activeSepsisCases;
    private int redPatients;

    private int predictedIcuDemand;
    private double icuUtilizationPercent;
    private double predictedUtilizationPercent;
    private String riskAssessment;
}
