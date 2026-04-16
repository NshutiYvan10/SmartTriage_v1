package com.smartTriage.smartTriage_server.module.quality.dto;

import com.smartTriage.smartTriage_server.common.enums.MetricPeriod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QualityMetricSnapshotResponse {

    private UUID id;
    private UUID hospitalId;
    private String hospitalName;
    private LocalDate snapshotDate;
    private MetricPeriod snapshotPeriod;

    // Volume
    private Integer totalPatients;
    private Integer totalAdmissions;
    private Integer totalDischarges;
    private Integer totalTransfers;
    private Integer totalDeaths;
    private Integer totalLeftWithoutBeingSeen;
    private Integer pediatricPatients;

    // Triage
    private Integer redPatients;
    private Integer orangePatients;
    private Integer yellowPatients;
    private Integer greenPatients;
    private Integer bluePatients;
    private Double averageTewsScore;
    private Integer retriageCount;
    private Integer systemTriggeredRetriages;

    // Time
    private Double averageWaitTimeMinutes;
    private Double averageDoorToTriageMinutes;
    private Double averageDoorToPhysicianMinutes;
    private Double averageTotalEdStayMinutes;
    private Double percentSeenWithinTarget;
    private Double medianWaitTimeMinutes;

    // Safety
    private Double sepsisScreeningRate;
    private Double sepsisBundleComplianceRate;
    private Double criticalLabTurnaroundMinutes;
    private Integer medicationErrorCount;
    private Integer safetyIncidentCount;

    // Capacity
    private Integer peakEdOccupancy;
    private Double averageEdOccupancy;
    private Double icuBedUtilizationPercent;
    private Double edBedUtilizationPercent;

    // Mortality
    private Double edMortalityRate;
    private Integer mortalityWithin24Hours;
}
