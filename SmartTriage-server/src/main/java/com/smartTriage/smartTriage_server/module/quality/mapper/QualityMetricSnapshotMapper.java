package com.smartTriage.smartTriage_server.module.quality.mapper;

import com.smartTriage.smartTriage_server.module.quality.dto.QualityMetricSnapshotResponse;
import com.smartTriage.smartTriage_server.module.quality.entity.QualityMetricSnapshot;

public final class QualityMetricSnapshotMapper {

    private QualityMetricSnapshotMapper() {
    }

    public static QualityMetricSnapshotResponse toResponse(QualityMetricSnapshot snapshot) {
        QualityMetricSnapshotResponse.QualityMetricSnapshotResponseBuilder builder =
                QualityMetricSnapshotResponse.builder()
                        .id(snapshot.getId())
                        .snapshotDate(snapshot.getSnapshotDate())
                        .snapshotPeriod(snapshot.getSnapshotPeriod())
                        // Volume
                        .totalPatients(snapshot.getTotalPatients())
                        .totalAdmissions(snapshot.getTotalAdmissions())
                        .totalDischarges(snapshot.getTotalDischarges())
                        .totalTransfers(snapshot.getTotalTransfers())
                        .totalDeaths(snapshot.getTotalDeaths())
                        .totalLeftWithoutBeingSeen(snapshot.getTotalLeftWithoutBeingSeen())
                        .pediatricPatients(snapshot.getPediatricPatients())
                        // Triage
                        .redPatients(snapshot.getRedPatients())
                        .orangePatients(snapshot.getOrangePatients())
                        .yellowPatients(snapshot.getYellowPatients())
                        .greenPatients(snapshot.getGreenPatients())
                        .bluePatients(snapshot.getBluePatients())
                        .averageTewsScore(snapshot.getAverageTewsScore())
                        .retriageCount(snapshot.getRetriageCount())
                        .systemTriggeredRetriages(snapshot.getSystemTriggeredRetriages())
                        // Time
                        .averageWaitTimeMinutes(snapshot.getAverageWaitTimeMinutes())
                        .averageDoorToTriageMinutes(snapshot.getAverageDoorToTriageMinutes())
                        .averageDoorToPhysicianMinutes(snapshot.getAverageDoorToPhysicianMinutes())
                        .averageTotalEdStayMinutes(snapshot.getAverageTotalEdStayMinutes())
                        .percentSeenWithinTarget(snapshot.getPercentSeenWithinTarget())
                        .medianWaitTimeMinutes(snapshot.getMedianWaitTimeMinutes())
                        // Safety
                        .sepsisScreeningRate(snapshot.getSepsisScreeningRate())
                        .sepsisBundleComplianceRate(snapshot.getSepsisBundleComplianceRate())
                        .criticalLabTurnaroundMinutes(snapshot.getCriticalLabTurnaroundMinutes())
                        .medicationErrorCount(snapshot.getMedicationErrorCount())
                        .safetyIncidentCount(snapshot.getSafetyIncidentCount())
                        // Capacity
                        .peakEdOccupancy(snapshot.getPeakEdOccupancy())
                        .averageEdOccupancy(snapshot.getAverageEdOccupancy())
                        .icuBedUtilizationPercent(snapshot.getIcuBedUtilizationPercent())
                        .edBedUtilizationPercent(snapshot.getEdBedUtilizationPercent())
                        // Mortality
                        .edMortalityRate(snapshot.getEdMortalityRate())
                        .mortalityWithin24Hours(snapshot.getMortalityWithin24Hours());

        if (snapshot.getHospital() != null) {
            builder.hospitalId(snapshot.getHospital().getId());
            builder.hospitalName(snapshot.getHospital().getName());
        }

        return builder.build();
    }
}
