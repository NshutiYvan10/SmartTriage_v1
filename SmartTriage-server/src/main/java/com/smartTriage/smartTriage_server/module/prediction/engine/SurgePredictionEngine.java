package com.smartTriage.smartTriage_server.module.prediction.engine;

import com.smartTriage.smartTriage_server.common.enums.SurgeRiskLevel;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.prediction.dto.IcuDemandPrediction;
import com.smartTriage.smartTriage_server.module.prediction.entity.SurgePrediction;
import com.smartTriage.smartTriage_server.module.quality.repository.QualityMetricSnapshotRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;

/**
 * SurgePredictionEngine — rule-based prediction engine with statistical heuristics
 * for forecasting ED surge risk and ICU bed demand.
 *
 * Algorithm:
 * 1. Current arrival rate (patients/hour over last 2 hours)
 * 2. Historical average for same day-of-week (from past 30 days of QualityMetricSnapshots)
 * 3. Seasonal adjustment for Rwanda (malaria season, rainy season, holidays)
 * 4. Current occupancy ratio
 * 5. ICU demand analysis
 * 6. Weighted surge risk score
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurgePredictionEngine {

    private final VisitRepository visitRepository;
    private final HospitalRepository hospitalRepository;
    private final QualityMetricSnapshotRepository snapshotRepository;

    private static final double OCCUPANCY_WEIGHT = 0.40;
    private static final double ARRIVAL_RATE_WEIGHT = 0.30;
    private static final double ICU_DEMAND_WEIGHT = 0.30;

    private static final double MALARIA_SEASON_MULTIPLIER = 1.30;
    private static final double RAINY_SEASON_MULTIPLIER = 1.15;
    private static final double HOLIDAY_MULTIPLIER = 0.85;

    /**
     * Generate a surge prediction for a hospital over a specified time horizon.
     */
    public SurgePrediction predictSurge(java.util.UUID hospitalId, int horizonHours) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        Instant now = Instant.now();
        int edCapacity = hospital.getEdCapacity() != null ? hospital.getEdCapacity() : 30;
        int icuCapacity = hospital.getIcuCapacity() != null ? hospital.getIcuCapacity() : 10;

        // Step 1: Calculate current arrival rate (patients/hour over last 2 hours)
        double currentArrivalRate = calculateArrivalRate(hospitalId, now);

        // Step 2: Historical average for this day of week
        double historicalAvg = getHistoricalAverage(hospitalId);

        // Step 3: Seasonal adjustment
        double seasonalFactor = calculateSeasonalFactor(now);

        // Step 4: Current occupancy
        List<VisitStatus> activeStatuses = List.of(
                VisitStatus.AWAITING_TRIAGE, VisitStatus.TRIAGED,
                VisitStatus.AWAITING_ASSESSMENT, VisitStatus.UNDER_ASSESSMENT,
                VisitStatus.UNDER_TREATMENT, VisitStatus.UNDER_OBSERVATION,
                VisitStatus.PENDING_DISPOSITION
        );
        List<Visit> activeVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, activeStatuses);
        int currentEdOccupancy = activeVisits.size();
        double occupancyRatio = edCapacity > 0 ? (double) currentEdOccupancy / edCapacity : 1.0;

        // Step 5: ICU demand
        int currentIcuPatients = (int) visitRepository
                .findActiveVisitsByStatuses(hospitalId, List.of(VisitStatus.ICU_ADMITTED))
                .size();

        long redOrangeCount = activeVisits.stream()
                .filter(v -> v.getCurrentTriageCategory() == TriageCategory.RED
                        || v.getCurrentTriageCategory() == TriageCategory.ORANGE)
                .count();

        // Step 6: Calculate predicted values
        double adjustedArrivalRate = currentArrivalRate * seasonalFactor;
        int predictedNewAdmissions = (int) Math.ceil(adjustedArrivalRate * horizonHours);
        int predictedRedPatients = (int) Math.ceil(redOrangeCount * 0.3 * horizonHours / 4.0);

        // ICU demand prediction
        double icuConversionRate = 0.15; // ~15% of RED patients convert to ICU
        int predictedIcuDemand = currentIcuPatients + (int) Math.ceil(redOrangeCount * icuConversionRate);

        // Step 7: Surge risk score (0-100)
        double occupancyScore = Math.min(occupancyRatio * 100, 100);

        double arrivalRateScore = 0;
        if (historicalAvg > 0) {
            arrivalRateScore = Math.min((adjustedArrivalRate / (historicalAvg / 24.0)) * 50, 100);
        } else {
            arrivalRateScore = Math.min(adjustedArrivalRate * 10, 100);
        }

        double icuScore = icuCapacity > 0
                ? Math.min(((double) predictedIcuDemand / icuCapacity) * 100, 100)
                : 50;

        double surgeRiskScore = (OCCUPANCY_WEIGHT * occupancyScore)
                + (ARRIVAL_RATE_WEIGHT * arrivalRateScore)
                + (ICU_DEMAND_WEIGHT * icuScore);
        surgeRiskScore = Math.min(Math.max(surgeRiskScore, 0), 100);

        // Step 8: Risk level
        SurgeRiskLevel riskLevel = classifyRiskLevel(surgeRiskScore);

        // Determine trend direction
        String trendDirection = determineTrendDirection(currentArrivalRate, historicalAvg);

        // Build notes
        String notes = buildPredictionNotes(currentArrivalRate, historicalAvg, seasonalFactor,
                occupancyRatio, riskLevel, edCapacity, currentEdOccupancy, horizonHours);

        return SurgePrediction.builder()
                .hospital(hospital)
                .predictedAt(now)
                .predictionHorizonHours(horizonHours)
                .predictedEdAdmissions(predictedNewAdmissions)
                .predictedIcuDemand(predictedIcuDemand)
                .predictedRedPatients(predictedRedPatients)
                .currentEdOccupancy(currentEdOccupancy)
                .currentIcuOccupancy(currentIcuPatients)
                .edCapacity(edCapacity)
                .icuCapacity(icuCapacity)
                .surgeRiskScore(Math.round(surgeRiskScore * 10.0) / 10.0)
                .surgeRiskLevel(riskLevel)
                .historicalAvgForPeriod(historicalAvg)
                .currentArrivalRate(Math.round(currentArrivalRate * 100.0) / 100.0)
                .trendDirection(trendDirection)
                .seasonalFactor(seasonalFactor)
                .notes(notes)
                .build();
    }

    /**
     * Predict ICU bed demand for a hospital.
     */
    public IcuDemandPrediction predictIcuDemand(java.util.UUID hospitalId, int horizonHours) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        int icuCapacity = hospital.getIcuCapacity() != null ? hospital.getIcuCapacity() : 10;

        // Current ICU patients
        int currentIcuPatients = visitRepository
                .findActiveVisitsByStatuses(hospitalId, List.of(VisitStatus.ICU_ADMITTED))
                .size();

        // Active visits for analysis
        List<VisitStatus> activeStatuses = List.of(
                VisitStatus.AWAITING_TRIAGE, VisitStatus.TRIAGED,
                VisitStatus.AWAITING_ASSESSMENT, VisitStatus.UNDER_ASSESSMENT,
                VisitStatus.UNDER_TREATMENT, VisitStatus.UNDER_OBSERVATION,
                VisitStatus.PENDING_DISPOSITION
        );
        List<Visit> activeVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, activeStatuses);

        // Count hemodynamically unstable patients (RED category as proxy)
        int hemodynamicallyUnstable = (int) activeVisits.stream()
                .filter(v -> v.getCurrentTriageCategory() == TriageCategory.RED)
                .count();

        // Count patients with high TEWS scores (proxy for sepsis/critical)
        int activeSepsisCases = (int) activeVisits.stream()
                .filter(v -> v.getCurrentTewsScore() != null && v.getCurrentTewsScore() >= 7)
                .count();

        // RED patients
        int redPatients = (int) activeVisits.stream()
                .filter(v -> v.getCurrentTriageCategory() == TriageCategory.RED)
                .count();

        // Historical ICU conversion rates
        double icuConversionRate = 0.20; // 20% of hemodynamically unstable convert
        double sepsisConversionRate = 0.30; // 30% of sepsis cases need ICU
        double redConversionRate = 0.10; // 10% of remaining RED patients

        int estimatedConversions = (int) Math.ceil(
                (hemodynamicallyUnstable * icuConversionRate)
                        + (activeSepsisCases * sepsisConversionRate)
                        + (redPatients * redConversionRate)
        );

        // Avoid double counting
        estimatedConversions = Math.max(estimatedConversions, 0);

        int predictedDemand = currentIcuPatients + estimatedConversions;
        double currentUtilization = icuCapacity > 0 ? (double) currentIcuPatients / icuCapacity * 100 : 0;
        double predictedUtilization = icuCapacity > 0 ? (double) predictedDemand / icuCapacity * 100 : 0;

        String riskAssessment;
        if (predictedUtilization >= 100) {
            riskAssessment = "CRITICAL: Predicted ICU demand exceeds capacity. Activate overflow protocols.";
        } else if (predictedUtilization >= 80) {
            riskAssessment = "HIGH: ICU near capacity. Consider early discharge review and resource mobilization.";
        } else if (predictedUtilization >= 60) {
            riskAssessment = "MODERATE: ICU utilization trending upward. Monitor closely.";
        } else {
            riskAssessment = "LOW: ICU capacity adequate for predicted demand.";
        }

        return IcuDemandPrediction.builder()
                .hospitalId(hospitalId)
                .predictedAt(Instant.now())
                .horizonHours(horizonHours)
                .currentIcuPatients(currentIcuPatients)
                .icuCapacity(icuCapacity)
                .hemodynamicallyUnstablePatients(hemodynamicallyUnstable)
                .activeSepsisCases(activeSepsisCases)
                .redPatients(redPatients)
                .predictedIcuDemand(predictedDemand)
                .icuUtilizationPercent(Math.round(currentUtilization * 10.0) / 10.0)
                .predictedUtilizationPercent(Math.round(predictedUtilization * 10.0) / 10.0)
                .riskAssessment(riskAssessment)
                .build();
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private double calculateArrivalRate(java.util.UUID hospitalId, Instant now) {
        // Count all visits that arrived in the last 2 hours
        Instant twoHoursAgo = now.minus(Duration.ofHours(2));
        List<VisitStatus> allStatuses = List.of(VisitStatus.values());
        List<Visit> recentVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, allStatuses);

        long arrivedInLast2Hours = recentVisits.stream()
                .filter(v -> v.getArrivalTime().isAfter(twoHoursAgo) && v.getArrivalTime().isBefore(now))
                .count();

        return arrivedInLast2Hours / 2.0; // patients per hour
    }

    private double getHistoricalAverage(java.util.UUID hospitalId) {
        LocalDate thirtyDaysAgo = LocalDate.now().minusDays(30);
        Double avg = snapshotRepository.findAverageTotalPatientsSince(hospitalId, thirtyDaysAgo);
        return avg != null ? avg : 0.0;
    }

    /**
     * Seasonal adjustment factors for Rwanda.
     * - Malaria season: March-May, October-December (1.3x)
     * - Rainy season: March-May, September-November (1.15x for trauma)
     * - Combined: take the max multiplier
     */
    private double calculateSeasonalFactor(Instant now) {
        Month month = now.atZone(ZoneId.of("Africa/Kigali")).getMonth();

        boolean isMalariaSeason = month == Month.MARCH || month == Month.APRIL || month == Month.MAY
                || month == Month.OCTOBER || month == Month.NOVEMBER || month == Month.DECEMBER;

        boolean isRainySeason = month == Month.MARCH || month == Month.APRIL || month == Month.MAY
                || month == Month.SEPTEMBER || month == Month.OCTOBER || month == Month.NOVEMBER;

        if (isMalariaSeason && isRainySeason) {
            return Math.max(MALARIA_SEASON_MULTIPLIER, RAINY_SEASON_MULTIPLIER);
        } else if (isMalariaSeason) {
            return MALARIA_SEASON_MULTIPLIER;
        } else if (isRainySeason) {
            return RAINY_SEASON_MULTIPLIER;
        }

        // Check for major Rwandan holidays (approximate — New Year, Genocide Memorial, Liberation)
        int dayOfYear = now.atZone(ZoneId.of("Africa/Kigali")).getDayOfYear();
        // Jan 1 (1), April 7 (97), July 4 (185), July 1 (182)
        if (dayOfYear == 1 || dayOfYear == 97 || dayOfYear == 182 || dayOfYear == 185) {
            return HOLIDAY_MULTIPLIER;
        }

        return 1.0;
    }

    private SurgeRiskLevel classifyRiskLevel(double score) {
        if (score >= 75) return SurgeRiskLevel.CRITICAL;
        if (score >= 50) return SurgeRiskLevel.HIGH;
        if (score >= 25) return SurgeRiskLevel.MODERATE;
        return SurgeRiskLevel.LOW;
    }

    private String determineTrendDirection(double currentRate, double historicalAvg) {
        if (historicalAvg <= 0) return "STABLE";
        double hourlyHistorical = historicalAvg / 24.0;
        if (hourlyHistorical <= 0) return "STABLE";

        double ratio = currentRate / hourlyHistorical;
        if (ratio > 1.2) return "INCREASING";
        if (ratio < 0.8) return "DECREASING";
        return "STABLE";
    }

    private String buildPredictionNotes(double arrivalRate, double historicalAvg,
                                        double seasonalFactor, double occupancyRatio,
                                        SurgeRiskLevel riskLevel, int edCapacity,
                                        int currentOccupancy, int horizonHours) {
        StringBuilder sb = new StringBuilder();
        sb.append("Surge prediction for next ").append(horizonHours).append(" hours.\n");
        sb.append("Current arrival rate: ").append(String.format("%.1f", arrivalRate)).append(" patients/hour.\n");
        sb.append("Historical daily average: ").append(String.format("%.0f", historicalAvg)).append(" patients/day.\n");
        sb.append("Seasonal factor: ").append(String.format("%.2f", seasonalFactor)).append(".\n");
        sb.append("Current ED occupancy: ").append(currentOccupancy).append("/").append(edCapacity);
        sb.append(" (").append(String.format("%.0f", occupancyRatio * 100)).append("%).\n");

        if (riskLevel == SurgeRiskLevel.CRITICAL) {
            sb.append("\nRECOMMENDED ACTIONS:\n");
            sb.append("- Cancel elective admissions\n");
            sb.append("- Call in off-duty staff\n");
            sb.append("- Prepare overflow areas\n");
            sb.append("- Notify hospital administration\n");
            sb.append("- Review patients for potential early discharge\n");
        } else if (riskLevel == SurgeRiskLevel.HIGH) {
            sb.append("\nRECOMMENDED ACTIONS:\n");
            sb.append("- Place off-duty staff on standby\n");
            sb.append("- Review ED capacity and bed availability\n");
            sb.append("- Expedite pending dispositions\n");
        }

        return sb.toString();
    }
}
