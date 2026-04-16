package com.smartTriage.smartTriage_server.module.reporting.engine;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MoH Report Generator — produces de-identified aggregate statistics by querying visit data.
 * All output is aggregate only; no patient names, national IDs, or identifiable information
 * is ever included in generated reports.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MohReportGenerator {

    private final VisitRepository visitRepository;
    private final HospitalRepository hospitalRepository;

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    /**
     * Generate a daily summary report for a hospital on a specific date.
     */
    public MohReport generateDailySummary(UUID hospitalId, LocalDate date) {
        log.info("Generating daily MoH summary for hospital {} on {}", hospitalId, date);

        Hospital hospital = findHospital(hospitalId);
        Instant dayStart = date.atStartOfDay(KIGALI).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(KIGALI).toInstant();

        List<Visit> visits = getVisitsForPeriod(hospitalId, dayStart, dayEnd);

        MohReport report = MohReport.builder()
                .hospital(hospital)
                .reportType(MohReportType.DAILY_SUMMARY)
                .reportPeriodStart(dayStart)
                .reportPeriodEnd(dayEnd)
                .generatedAt(Instant.now())
                .status(ReportStatus.GENERATED)
                .build();

        populateReportData(report, visits);

        log.info("Daily summary generated for hospital {}: {} ED visits", hospital.getName(), report.getTotalEdVisits());
        return report;
    }

    /**
     * Generate a weekly surveillance report aggregating 7 days of data.
     */
    public MohReport generateWeeklySurveillance(UUID hospitalId, LocalDate weekStart) {
        log.info("Generating weekly surveillance for hospital {} starting {}", hospitalId, weekStart);

        Hospital hospital = findHospital(hospitalId);
        LocalDate weekEnd = weekStart.plusDays(7);
        Instant start = weekStart.atStartOfDay(KIGALI).toInstant();
        Instant end = weekEnd.atStartOfDay(KIGALI).toInstant();

        List<Visit> visits = getVisitsForPeriod(hospitalId, start, end);

        MohReport report = MohReport.builder()
                .hospital(hospital)
                .reportType(MohReportType.WEEKLY_SURVEILLANCE)
                .reportPeriodStart(start)
                .reportPeriodEnd(end)
                .generatedAt(Instant.now())
                .status(ReportStatus.GENERATED)
                .build();

        populateReportData(report, visits);

        log.info("Weekly surveillance generated for hospital {}: {} ED visits over 7 days",
                hospital.getName(), report.getTotalEdVisits());
        return report;
    }

    /**
     * Generate full monthly statistics for a hospital.
     */
    public MohReport generateMonthlyStatistics(UUID hospitalId, YearMonth month) {
        log.info("Generating monthly statistics for hospital {} for {}", hospitalId, month);

        Hospital hospital = findHospital(hospitalId);
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth().plusDays(1);
        Instant start = monthStart.atStartOfDay(KIGALI).toInstant();
        Instant end = monthEnd.atStartOfDay(KIGALI).toInstant();

        List<Visit> visits = getVisitsForPeriod(hospitalId, start, end);

        MohReport report = MohReport.builder()
                .hospital(hospital)
                .reportType(MohReportType.MONTHLY_STATISTICS)
                .reportPeriodStart(start)
                .reportPeriodEnd(end)
                .generatedAt(Instant.now())
                .status(ReportStatus.GENERATED)
                .build();

        populateReportData(report, visits);

        log.info("Monthly statistics generated for hospital {} for {}: {} ED visits",
                hospital.getName(), month, report.getTotalEdVisits());
        return report;
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private Hospital findHospital(UUID hospitalId) {
        return hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));
    }

    /**
     * Fetch all visits for a hospital within the given time range.
     */
    private List<Visit> getVisitsForPeriod(UUID hospitalId, Instant start, Instant end) {
        List<VisitStatus> allStatuses = List.of(VisitStatus.values());
        List<Visit> allVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, allStatuses);

        return allVisits.stream()
                .filter(v -> !v.getArrivalTime().isBefore(start) && v.getArrivalTime().isBefore(end))
                .collect(Collectors.toList());
    }

    /**
     * Populate a MohReport with aggregate, de-identified statistics from the given visits.
     */
    private void populateReportData(MohReport report, List<Visit> visits) {
        report.setTotalEdVisits(visits.size());

        // Count triaged visits
        long triagedCount = visits.stream()
                .filter(v -> v.getCurrentTriageCategory() != null)
                .count();
        report.setTotalTriaged((int) triagedCount);

        // Triage category breakdown as JSON
        Map<TriageCategory, Long> categoryMap = visits.stream()
                .filter(v -> v.getCurrentTriageCategory() != null)
                .collect(Collectors.groupingBy(Visit::getCurrentTriageCategory, Collectors.counting()));
        report.setTriageCategoryBreakdown(buildTriageCategoryJson(categoryMap));

        // Average wait time: arrivalTime to triageTime
        OptionalDouble avgWait = visits.stream()
                .filter(v -> v.getTriageTime() != null)
                .mapToLong(v -> Duration.between(v.getArrivalTime(), v.getTriageTime()).toMinutes())
                .filter(min -> min >= 0)
                .average();
        report.setAverageWaitTimeMinutes(avgWait.orElse(0.0));

        // Disposition counts
        report.setMortalityCount((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.DECEASED)
                .count());

        report.setLeftWithoutBeingSeenCount((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.LEFT_WITHOUT_BEING_SEEN)
                .count());

        report.setAdmissionCount((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.ADMITTED)
                .count());

        report.setIcuAdmissionCount((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.ICU_ADMITTED)
                .count());

        report.setTransferCount((int) visits.stream()
                .filter(v -> v.getStatus() == VisitStatus.TRANSFERRED)
                .count());

        // Pediatric visits
        report.setPediatricVisitCount((int) visits.stream()
                .filter(Visit::isPediatric)
                .count());

        // Top chief complaints as JSON array (aggregate counts only, no patient identifiers)
        Map<String, Long> complaintCounts = visits.stream()
                .filter(v -> v.getChiefComplaint() != null && !v.getChiefComplaint().isBlank())
                .collect(Collectors.groupingBy(
                        v -> v.getChiefComplaint().trim().toLowerCase(),
                        Collectors.counting()));
        report.setTopChiefComplaints(buildTopItemsJson(complaintCounts, 10));

        // Average length of stay (arrival to disposition)
        OptionalDouble avgStay = visits.stream()
                .filter(v -> v.getDispositionTime() != null)
                .mapToLong(v -> Duration.between(v.getArrivalTime(), v.getDispositionTime()).toMinutes())
                .filter(min -> min >= 0)
                .average();
        report.setAverageLengthOfStayMinutes(avgStay.orElse(0.0));

        // Counts that require integration with sub-modules default to 0 when data is unavailable
        if (report.getMalariaPositiveCount() == null) {
            report.setMalariaPositiveCount(0);
        }
        if (report.getSepsisScreenedCount() == null) {
            report.setSepsisScreenedCount(0);
        }
        if (report.getIsolationActivatedCount() == null) {
            report.setIsolationActivatedCount(0);
        }
    }

    /**
     * Build JSON string for triage category breakdown.
     * Example: {"RED":5,"ORANGE":20,"YELLOW":50,"GREEN":100}
     */
    private String buildTriageCategoryJson(Map<TriageCategory, Long> categoryMap) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (TriageCategory cat : TriageCategory.values()) {
            long count = categoryMap.getOrDefault(cat, 0L);
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(cat.name()).append("\":").append(count);
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Build JSON array of top N items with counts.
     * Example: [{"item":"fever","count":25},{"item":"malaria","count":18}]
     */
    private String buildTopItemsJson(Map<String, Long> countMap, int topN) {
        List<Map.Entry<String, Long>> sorted = countMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, Long> entry : sorted) {
            if (!first) {
                sb.append(",");
            }
            sb.append("{\"item\":\"")
                    .append(entry.getKey().replace("\"", "\\\""))
                    .append("\",\"count\":")
                    .append(entry.getValue())
                    .append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }
}
