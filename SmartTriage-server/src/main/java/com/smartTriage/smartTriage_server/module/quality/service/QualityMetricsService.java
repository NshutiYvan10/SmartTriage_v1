package com.smartTriage.smartTriage_server.module.quality.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.quality.dto.RealTimeMetricsResponse;
import com.smartTriage.smartTriage_server.module.quality.entity.QualityMetricSnapshot;
import com.smartTriage.smartTriage_server.module.quality.repository.QualityMetricSnapshotRepository;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * QualityMetricsService — computes and persists quality metrics per Rwanda MoH standards.
 *
 * Tracks KPIs including volume, triage distribution, time performance (door-to-triage,
 * door-to-physician), safety indicators, capacity utilization, and mortality metrics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QualityMetricsService {

    private final QualityMetricSnapshotRepository snapshotRepository;
    private final VisitRepository visitRepository;
    private final HospitalRepository hospitalRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final InvestigationRepository investigationRepository;

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    /**
     * Compute all metrics for a specific day and persist as a QualityMetricSnapshot.
     */
    @Transactional
    public QualityMetricSnapshot computeDailyMetrics(UUID hospitalId, LocalDate date) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        log.info("Computing daily metrics for hospital {} on {}", hospital.getName(), date);

        // Check if snapshot already exists — update if so
        Optional<QualityMetricSnapshot> existing = snapshotRepository
                .findByHospitalIdAndSnapshotDateAndSnapshotPeriodAndIsActiveTrue(
                        hospitalId, date, MetricPeriod.DAILY);

        QualityMetricSnapshot snapshot = existing.orElseGet(() ->
                QualityMetricSnapshot.builder()
                        .hospital(hospital)
                        .snapshotDate(date)
                        .snapshotPeriod(MetricPeriod.DAILY)
                        .build()
        );

        // Time boundaries for the day
        Instant dayStart = date.atStartOfDay(KIGALI).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(KIGALI).toInstant();

        // Get all visits that arrived on this date
        List<Visit> allVisits = getAllVisitsForDateRange(hospitalId, dayStart, dayEnd);

        // Volume metrics
        populateVolumeMetrics(snapshot, allVisits);

        // Triage metrics
        populateTriageMetrics(snapshot, allVisits);

        // Time metrics
        populateTimeMetrics(snapshot, allVisits);

        // Capacity metrics
        populateCapacityMetrics(snapshot, hospital, allVisits, hospitalId);

        // Mortality
        populateMortalityMetrics(snapshot, allVisits, dayStart);

        // Safety metrics (basic — advanced requires integration with incident system)
        snapshot.setMedicationErrorCount(0);
        snapshot.setSafetyIncidentCount(0);
        snapshot.setSepsisScreeningRate(0.0);
        snapshot.setSepsisBundleComplianceRate(0.0);
        snapshot.setCriticalLabTurnaroundMinutes(0.0);

        snapshot = snapshotRepository.save(snapshot);
        log.info("Daily metrics computed for hospital {} on {}: {} total patients",
                hospital.getName(), date, snapshot.getTotalPatients());
        return snapshot;
    }

    /**
     * Aggregate daily snapshots into a weekly snapshot.
     */
    @Transactional
    public QualityMetricSnapshot computeWeeklyMetrics(UUID hospitalId, LocalDate weekStart) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        LocalDate weekEnd = weekStart.plusDays(6);
        List<QualityMetricSnapshot> dailySnapshots = snapshotRepository
                .findDailySnapshotsInRange(hospitalId, weekStart, weekEnd);

        if (dailySnapshots.isEmpty()) {
            log.warn("No daily snapshots found for weekly aggregation: hospital {} week starting {}",
                    hospital.getName(), weekStart);
            return null;
        }

        Optional<QualityMetricSnapshot> existing = snapshotRepository
                .findByHospitalIdAndSnapshotDateAndSnapshotPeriodAndIsActiveTrue(
                        hospitalId, weekStart, MetricPeriod.WEEKLY);

        QualityMetricSnapshot weekly = existing.orElseGet(() ->
                QualityMetricSnapshot.builder()
                        .hospital(hospital)
                        .snapshotDate(weekStart)
                        .snapshotPeriod(MetricPeriod.WEEKLY)
                        .build()
        );

        aggregateSnapshots(weekly, dailySnapshots);

        weekly = snapshotRepository.save(weekly);
        log.info("Weekly metrics computed for hospital {} week of {}", hospital.getName(), weekStart);
        return weekly;
    }

    /**
     * Aggregate daily snapshots into a monthly snapshot.
     */
    @Transactional
    public QualityMetricSnapshot computeMonthlyMetrics(UUID hospitalId, YearMonth month) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        List<QualityMetricSnapshot> dailySnapshots = snapshotRepository
                .findDailySnapshotsInRange(hospitalId, monthStart, monthEnd);

        if (dailySnapshots.isEmpty()) {
            log.warn("No daily snapshots found for monthly aggregation: hospital {} month {}",
                    hospital.getName(), month);
            return null;
        }

        Optional<QualityMetricSnapshot> existing = snapshotRepository
                .findByHospitalIdAndSnapshotDateAndSnapshotPeriodAndIsActiveTrue(
                        hospitalId, monthStart, MetricPeriod.MONTHLY);

        QualityMetricSnapshot monthly = existing.orElseGet(() ->
                QualityMetricSnapshot.builder()
                        .hospital(hospital)
                        .snapshotDate(monthStart)
                        .snapshotPeriod(MetricPeriod.MONTHLY)
                        .build()
        );

        aggregateSnapshots(monthly, dailySnapshots);

        monthly = snapshotRepository.save(monthly);
        log.info("Monthly metrics computed for hospital {} month {}", hospital.getName(), month);
        return monthly;
    }

    /**
     * Live metrics from active visits — calculated on the fly, not persisted.
     */
    public RealTimeMetricsResponse getRealTimeMetrics(UUID hospitalId) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        List<VisitStatus> activeStatuses = List.of(
                VisitStatus.AWAITING_TRIAGE, VisitStatus.TRIAGED,
                VisitStatus.AWAITING_ASSESSMENT, VisitStatus.UNDER_ASSESSMENT,
                VisitStatus.UNDER_TREATMENT, VisitStatus.UNDER_OBSERVATION,
                VisitStatus.PENDING_DISPOSITION
        );

        List<Visit> activeVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, activeStatuses);

        int edCapacity = hospital.getEdCapacity() != null ? hospital.getEdCapacity() : 1;

        // Count by triage category
        Map<TriageCategory, Long> categoryCount = activeVisits.stream()
                .filter(v -> v.getCurrentTriageCategory() != null)
                .collect(Collectors.groupingBy(Visit::getCurrentTriageCategory, Collectors.counting()));

        // Count by status
        Map<VisitStatus, Long> statusCount = activeVisits.stream()
                .collect(Collectors.groupingBy(Visit::getStatus, Collectors.counting()));

        // Average current wait time for patients awaiting triage
        double avgWait = activeVisits.stream()
                .filter(v -> v.getStatus() == VisitStatus.AWAITING_TRIAGE)
                .mapToLong(v -> Duration.between(v.getArrivalTime(), Instant.now()).toMinutes())
                .average()
                .orElse(0.0);

        // Pending investigations count across all active visits
        long pendingInvestigations = 0;
        for (Visit visit : activeVisits) {
            pendingInvestigations += investigationRepository
                    .countByVisitIdAndStatusAndIsActiveTrue(visit.getId(), InvestigationStatus.ORDERED);
        }

        // Pediatric count
        int pediatricCount = (int) activeVisits.stream()
                .filter(Visit::isPediatric)
                .count();

        return RealTimeMetricsResponse.builder()
                .hospitalId(hospitalId)
                .hospitalName(hospital.getName())
                .calculatedAt(Instant.now())
                .currentEdOccupancy(activeVisits.size())
                .edCapacity(edCapacity)
                .edOccupancyPercent(edCapacity > 0 ? (double) activeVisits.size() / edCapacity * 100 : 0)
                .redPatients(categoryCount.getOrDefault(TriageCategory.RED, 0L).intValue())
                .orangePatients(categoryCount.getOrDefault(TriageCategory.ORANGE, 0L).intValue())
                .yellowPatients(categoryCount.getOrDefault(TriageCategory.YELLOW, 0L).intValue())
                .greenPatients(categoryCount.getOrDefault(TriageCategory.GREEN, 0L).intValue())
                .bluePatients(categoryCount.getOrDefault(TriageCategory.BLUE, 0L).intValue())
                .averageCurrentWaitMinutes(avgWait)
                .patientsAwaitingTriage(statusCount.getOrDefault(VisitStatus.AWAITING_TRIAGE, 0L).intValue())
                .patientsAwaitingAssessment(statusCount.getOrDefault(VisitStatus.AWAITING_ASSESSMENT, 0L).intValue())
                .patientsUnderTreatment(statusCount.getOrDefault(VisitStatus.UNDER_TREATMENT, 0L).intValue())
                .patientsUnderObservation(statusCount.getOrDefault(VisitStatus.UNDER_OBSERVATION, 0L).intValue())
                .pendingDisposition(statusCount.getOrDefault(VisitStatus.PENDING_DISPOSITION, 0L).intValue())
                .pendingInvestigations(pendingInvestigations)
                .activeAlerts(0)
                .unacknowledgedAlerts(0)
                .pediatricPatients(pediatricCount)
                .build();
    }

    /**
     * Get daily snapshot for a specific date.
     */
    public QualityMetricSnapshot getMetricsByDate(UUID hospitalId, LocalDate date) {
        return snapshotRepository.findDailyByHospitalAndDate(hospitalId, date)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "QualityMetricSnapshot", "date", date));
    }

    /**
     * Get snapshots for a date range.
     */
    public List<QualityMetricSnapshot> getMetricsByRange(UUID hospitalId, LocalDate from, LocalDate to) {
        return snapshotRepository.findByHospitalAndDateRange(hospitalId, from, to);
    }

    /**
     * Get last N periods for trend analysis.
     */
    public List<QualityMetricSnapshot> getTrends(UUID hospitalId, MetricPeriod period, int count) {
        return snapshotRepository.findTrends(hospitalId, period, PageRequest.of(0, count));
    }

    // ====================================================================
    // HELPER METHODS
    // ====================================================================

    private List<Visit> getAllVisitsForDateRange(UUID hospitalId, Instant start, Instant end) {
        // Get all visits that have statuses including terminal ones for the date
        List<VisitStatus> allStatuses = List.of(VisitStatus.values());
        List<Visit> allVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, allStatuses);

        return allVisits.stream()
                .filter(v -> !v.getArrivalTime().isBefore(start) && v.getArrivalTime().isBefore(end))
                .collect(Collectors.toList());
    }

    private void populateVolumeMetrics(QualityMetricSnapshot snapshot, List<Visit> visits) {
        snapshot.setTotalPatients(visits.size());

        snapshot.setTotalAdmissions((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.ADMITTED || v.getStatus() == VisitStatus.ICU_ADMITTED)
                .count());

        snapshot.setTotalDischarges((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.DISCHARGED)
                .count());

        snapshot.setTotalTransfers((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.TRANSFERRED)
                .count());

        snapshot.setTotalDeaths((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.DECEASED)
                .count());

        snapshot.setTotalLeftWithoutBeingSeen((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.LEFT_WITHOUT_BEING_SEEN)
                .count());

        snapshot.setPediatricPatients((int) visits.stream()
                .filter(Visit::isPediatric)
                .count());
    }

    private void populateTriageMetrics(QualityMetricSnapshot snapshot, List<Visit> visits) {
        Map<TriageCategory, Long> categories = visits.stream()
                .filter(v -> v.getCurrentTriageCategory() != null)
                .collect(Collectors.groupingBy(Visit::getCurrentTriageCategory, Collectors.counting()));

        snapshot.setRedPatients(categories.getOrDefault(TriageCategory.RED, 0L).intValue());
        snapshot.setOrangePatients(categories.getOrDefault(TriageCategory.ORANGE, 0L).intValue());
        snapshot.setYellowPatients(categories.getOrDefault(TriageCategory.YELLOW, 0L).intValue());
        snapshot.setGreenPatients(categories.getOrDefault(TriageCategory.GREEN, 0L).intValue());
        snapshot.setBluePatients(categories.getOrDefault(TriageCategory.BLUE, 0L).intValue());

        // Average TEWS score
        OptionalDouble avgTews = visits.stream()
                .filter(v -> v.getCurrentTewsScore() != null)
                .mapToInt(Visit::getCurrentTewsScore)
                .average();
        snapshot.setAverageTewsScore(avgTews.orElse(0.0));

        // Retriage counts
        int totalRetriages = visits.stream().mapToInt(Visit::getRetriageCount).sum();
        snapshot.setRetriageCount(totalRetriages);

        // System-triggered retriages — counted from triage records
        int systemTriggered = 0;
        for (Visit visit : visits) {
            List<TriageRecord> records = triageRecordRepository
                    .findByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visit.getId(), PageRequest.of(0, 100))
                    .getContent();
            systemTriggered += (int) records.stream()
                    .filter(TriageRecord::isSystemTriggered)
                    .count();
        }
        snapshot.setSystemTriggeredRetriages(systemTriggered);
    }

    private void populateTimeMetrics(QualityMetricSnapshot snapshot, List<Visit> visits) {
        // Door-to-Triage times
        List<Long> doorToTriageTimes = visits.stream()
                .filter(v -> v.getTriageTime() != null)
                .map(v -> Duration.between(v.getArrivalTime(), v.getTriageTime()).toMinutes())
                .filter(min -> min >= 0)
                .collect(Collectors.toList());

        if (!doorToTriageTimes.isEmpty()) {
            snapshot.setAverageDoorToTriageMinutes(
                    doorToTriageTimes.stream().mapToLong(Long::longValue).average().orElse(0));
            snapshot.setAverageWaitTimeMinutes(
                    doorToTriageTimes.stream().mapToLong(Long::longValue).average().orElse(0));

            // Median
            List<Long> sorted = doorToTriageTimes.stream().sorted().collect(Collectors.toList());
            int mid = sorted.size() / 2;
            double median = sorted.size() % 2 == 0
                    ? (sorted.get(mid - 1) + sorted.get(mid)) / 2.0
                    : sorted.get(mid);
            snapshot.setMedianWaitTimeMinutes(median);
        } else {
            snapshot.setAverageDoorToTriageMinutes(0.0);
            snapshot.setAverageWaitTimeMinutes(0.0);
            snapshot.setMedianWaitTimeMinutes(0.0);
        }

        // Door-to-Physician
        List<Long> doorToPhysicianTimes = visits.stream()
                .filter(v -> v.getAssessmentStartTime() != null)
                .map(v -> Duration.between(v.getArrivalTime(), v.getAssessmentStartTime()).toMinutes())
                .filter(min -> min >= 0)
                .collect(Collectors.toList());

        snapshot.setAverageDoorToPhysicianMinutes(
                doorToPhysicianTimes.stream().mapToLong(Long::longValue).average().orElse(0));

        // Total ED Stay
        List<Long> totalStayTimes = visits.stream()
                .filter(v -> v.getDispositionTime() != null)
                .map(v -> Duration.between(v.getArrivalTime(), v.getDispositionTime()).toMinutes())
                .filter(min -> min >= 0)
                .collect(Collectors.toList());

        snapshot.setAverageTotalEdStayMinutes(
                totalStayTimes.stream().mapToLong(Long::longValue).average().orElse(0));

        // Percent seen within SATS target
        long totalTriaged = visits.stream().filter(v -> v.getTriageTime() != null && v.getCurrentTriageCategory() != null).count();
        if (totalTriaged > 0) {
            long withinTarget = visits.stream()
                    .filter(v -> v.getTriageTime() != null && v.getCurrentTriageCategory() != null)
                    .filter(v -> {
                        long waitMinutes = Duration.between(v.getArrivalTime(), v.getTriageTime()).toMinutes();
                        int maxWait = v.getCurrentTriageCategory().getMaxWaitMinutes();
                        return maxWait < 0 || waitMinutes <= maxWait;
                    })
                    .count();
            snapshot.setPercentSeenWithinTarget((double) withinTarget / totalTriaged * 100);
        } else {
            snapshot.setPercentSeenWithinTarget(0.0);
        }
    }

    private void populateCapacityMetrics(QualityMetricSnapshot snapshot, Hospital hospital,
                                         List<Visit> visits, UUID hospitalId) {
        int edCapacity = hospital.getEdCapacity() != null ? hospital.getEdCapacity() : 1;
        int icuCapacity = hospital.getIcuCapacity() != null ? hospital.getIcuCapacity() : 1;

        // Peak and average ED occupancy (approximate — use total active at any point)
        snapshot.setPeakEdOccupancy(visits.size());
        snapshot.setAverageEdOccupancy((double) visits.size());

        // Utilization
        snapshot.setEdBedUtilizationPercent(
                edCapacity > 0 ? (double) visits.size() / edCapacity * 100 : 0.0);

        long icuPatients = visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.ICU_ADMITTED)
                .count();
        snapshot.setIcuBedUtilizationPercent(
                icuCapacity > 0 ? (double) icuPatients / icuCapacity * 100 : 0.0);
    }

    private void populateMortalityMetrics(QualityMetricSnapshot snapshot, List<Visit> visits,
                                          Instant dayStart) {
        int totalDeaths = (int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.DECEASED)
                .count();

        snapshot.setEdMortalityRate(
                visits.isEmpty() ? 0.0 : (double) totalDeaths / visits.size() * 100);

        // Deaths within 24 hours of arrival
        int deathsWithin24h = (int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.DECEASED)
                .filter(v -> v.getDispositionTime() != null)
                .filter(v -> Duration.between(v.getArrivalTime(), v.getDispositionTime()).toHours() <= 24)
                .count();
        snapshot.setMortalityWithin24Hours(deathsWithin24h);
    }

    private void aggregateSnapshots(QualityMetricSnapshot aggregate, List<QualityMetricSnapshot> dailies) {
        // Sum volume metrics
        aggregate.setTotalPatients(dailies.stream().mapToInt(s -> nvl(s.getTotalPatients())).sum());
        aggregate.setTotalAdmissions(dailies.stream().mapToInt(s -> nvl(s.getTotalAdmissions())).sum());
        aggregate.setTotalDischarges(dailies.stream().mapToInt(s -> nvl(s.getTotalDischarges())).sum());
        aggregate.setTotalTransfers(dailies.stream().mapToInt(s -> nvl(s.getTotalTransfers())).sum());
        aggregate.setTotalDeaths(dailies.stream().mapToInt(s -> nvl(s.getTotalDeaths())).sum());
        aggregate.setTotalLeftWithoutBeingSeen(dailies.stream().mapToInt(s -> nvl(s.getTotalLeftWithoutBeingSeen())).sum());
        aggregate.setPediatricPatients(dailies.stream().mapToInt(s -> nvl(s.getPediatricPatients())).sum());

        // Sum triage counts
        aggregate.setRedPatients(dailies.stream().mapToInt(s -> nvl(s.getRedPatients())).sum());
        aggregate.setOrangePatients(dailies.stream().mapToInt(s -> nvl(s.getOrangePatients())).sum());
        aggregate.setYellowPatients(dailies.stream().mapToInt(s -> nvl(s.getYellowPatients())).sum());
        aggregate.setGreenPatients(dailies.stream().mapToInt(s -> nvl(s.getGreenPatients())).sum());
        aggregate.setBluePatients(dailies.stream().mapToInt(s -> nvl(s.getBluePatients())).sum());
        aggregate.setRetriageCount(dailies.stream().mapToInt(s -> nvl(s.getRetriageCount())).sum());
        aggregate.setSystemTriggeredRetriages(dailies.stream().mapToInt(s -> nvl(s.getSystemTriggeredRetriages())).sum());

        // Average the averages (weighted by totalPatients would be better but sufficient for this)
        aggregate.setAverageTewsScore(dailies.stream().mapToDouble(s -> nvlD(s.getAverageTewsScore())).average().orElse(0));
        aggregate.setAverageWaitTimeMinutes(dailies.stream().mapToDouble(s -> nvlD(s.getAverageWaitTimeMinutes())).average().orElse(0));
        aggregate.setAverageDoorToTriageMinutes(dailies.stream().mapToDouble(s -> nvlD(s.getAverageDoorToTriageMinutes())).average().orElse(0));
        aggregate.setAverageDoorToPhysicianMinutes(dailies.stream().mapToDouble(s -> nvlD(s.getAverageDoorToPhysicianMinutes())).average().orElse(0));
        aggregate.setAverageTotalEdStayMinutes(dailies.stream().mapToDouble(s -> nvlD(s.getAverageTotalEdStayMinutes())).average().orElse(0));
        aggregate.setPercentSeenWithinTarget(dailies.stream().mapToDouble(s -> nvlD(s.getPercentSeenWithinTarget())).average().orElse(0));
        aggregate.setMedianWaitTimeMinutes(dailies.stream().mapToDouble(s -> nvlD(s.getMedianWaitTimeMinutes())).average().orElse(0));

        // Safety
        aggregate.setMedicationErrorCount(dailies.stream().mapToInt(s -> nvl(s.getMedicationErrorCount())).sum());
        aggregate.setSafetyIncidentCount(dailies.stream().mapToInt(s -> nvl(s.getSafetyIncidentCount())).sum());
        aggregate.setSepsisScreeningRate(dailies.stream().mapToDouble(s -> nvlD(s.getSepsisScreeningRate())).average().orElse(0));
        aggregate.setSepsisBundleComplianceRate(dailies.stream().mapToDouble(s -> nvlD(s.getSepsisBundleComplianceRate())).average().orElse(0));
        aggregate.setCriticalLabTurnaroundMinutes(dailies.stream().mapToDouble(s -> nvlD(s.getCriticalLabTurnaroundMinutes())).average().orElse(0));

        // Capacity — peak is max, average is average
        aggregate.setPeakEdOccupancy(dailies.stream().mapToInt(s -> nvl(s.getPeakEdOccupancy())).max().orElse(0));
        aggregate.setAverageEdOccupancy(dailies.stream().mapToDouble(s -> nvlD(s.getAverageEdOccupancy())).average().orElse(0));
        aggregate.setIcuBedUtilizationPercent(dailies.stream().mapToDouble(s -> nvlD(s.getIcuBedUtilizationPercent())).average().orElse(0));
        aggregate.setEdBedUtilizationPercent(dailies.stream().mapToDouble(s -> nvlD(s.getEdBedUtilizationPercent())).average().orElse(0));

        // Mortality
        int totalPatients = nvl(aggregate.getTotalPatients());
        int totalDeaths = nvl(aggregate.getTotalDeaths());
        aggregate.setEdMortalityRate(totalPatients > 0 ? (double) totalDeaths / totalPatients * 100 : 0.0);
        aggregate.setMortalityWithin24Hours(dailies.stream().mapToInt(s -> nvl(s.getMortalityWithin24Hours())).sum());
    }

    private int nvl(Integer val) {
        return val != null ? val : 0;
    }

    private double nvlD(Double val) {
        return val != null ? val : 0.0;
    }
}
