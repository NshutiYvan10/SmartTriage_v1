package com.smartTriage.smartTriage_server.module.reporting.service;

import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import com.smartTriage.smartTriage_server.common.enums.ReportStatus;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.reporting.engine.MohReportGenerator;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.reporting.repository.MohReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing MoH reports — generation, submission, and review lifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MohReportService {

    private final MohReportRepository mohReportRepository;
    private final MohReportGenerator mohReportGenerator;

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    /**
     * Generate a report for the specified hospital, type, and period.
     * Delegates to the appropriate generator method based on report type.
     */
    @Transactional
    public MohReport generateReport(UUID hospitalId, MohReportType type,
                                    LocalDate periodStart, LocalDate periodEnd) {
        log.info("Generating {} report for hospital {} from {} to {}",
                type, hospitalId, periodStart, periodEnd);

        MohReport report;

        switch (type) {
            case DAILY_SUMMARY:
                report = mohReportGenerator.generateDailySummary(hospitalId, periodStart);
                break;
            case WEEKLY_SURVEILLANCE:
                report = mohReportGenerator.generateWeeklySurveillance(hospitalId, periodStart);
                break;
            case MONTHLY_STATISTICS:
                report = mohReportGenerator.generateMonthlyStatistics(
                        hospitalId, YearMonth.from(periodStart));
                break;
            default:
                // For QUARTERLY_REVIEW, ANNUAL_REPORT, OUTBREAK_NOTIFICATION, MORTALITY_REVIEW
                // use the general period-based approach
                report = generateGenericReport(hospitalId, type, periodStart, periodEnd);
                break;
        }

        report = mohReportRepository.save(report);
        log.info("Report generated and saved with ID: {}", report.getId());
        return report;
    }

    /**
     * Submit a report for MoH review.
     */
    @Transactional
    public MohReport submitReport(UUID reportId) {
        MohReport report = findReport(reportId);

        if (report.getStatus() != ReportStatus.GENERATED && report.getStatus() != ReportStatus.DRAFT) {
            throw new IllegalStateException(
                    "Report can only be submitted when in DRAFT or GENERATED status. Current status: " + report.getStatus());
        }

        report.setStatus(ReportStatus.SUBMITTED);
        report.setSubmittedAt(Instant.now());

        report = mohReportRepository.save(report);
        log.info("Report {} submitted for MoH review", reportId);
        return report;
    }

    /**
     * Reject a submitted report with a reason.
     */
    @Transactional
    public MohReport rejectReport(UUID reportId, String reason) {
        MohReport report = findReport(reportId);

        if (report.getStatus() != ReportStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Only submitted reports can be rejected. Current status: " + report.getStatus());
        }

        report.setStatus(ReportStatus.REJECTED);
        report.setRejectionReason(reason);

        report = mohReportRepository.save(report);
        log.info("Report {} rejected: {}", reportId, reason);
        return report;
    }

    /**
     * Accept a submitted report.
     */
    @Transactional
    public MohReport acceptReport(UUID reportId) {
        MohReport report = findReport(reportId);

        if (report.getStatus() != ReportStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Only submitted reports can be accepted. Current status: " + report.getStatus());
        }

        report.setStatus(ReportStatus.ACCEPTED);

        report = mohReportRepository.save(report);
        log.info("Report {} accepted", reportId);
        return report;
    }

    /**
     * List all reports for a hospital with pagination.
     */
    public Page<MohReport> getReportsForHospital(UUID hospitalId, Pageable pageable) {
        return mohReportRepository.findByHospitalIdAndIsActiveTrueOrderByReportPeriodStartDesc(
                hospitalId, pageable);
    }

    /**
     * Get a single report by ID.
     */
    public MohReport getReport(UUID reportId) {
        return findReport(reportId);
    }

    /**
     * Find a specific report by hospital, type, and period.
     */
    public Optional<MohReport> getReportByTypeAndPeriod(UUID hospitalId, MohReportType type,
                                                         Instant start, Instant end) {
        return mohReportRepository.findByHospitalAndTypeAndPeriod(hospitalId, type, start, end);
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private MohReport findReport(UUID reportId) {
        return mohReportRepository.findByIdAndIsActiveTrue(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("MohReport", "id", reportId));
    }

    /**
     * Generate a generic period-based report for types that do not have specialized generators.
     */
    private MohReport generateGenericReport(UUID hospitalId, MohReportType type,
                                             LocalDate periodStart, LocalDate periodEnd) {
        Instant start = periodStart.atStartOfDay(KIGALI).toInstant();
        Instant end = periodEnd.plusDays(1).atStartOfDay(KIGALI).toInstant();

        // Reuse the daily summary generator logic for the full period
        MohReport report = mohReportGenerator.generateDailySummary(hospitalId, periodStart);
        report.setReportType(type);
        report.setReportPeriodStart(start);
        report.setReportPeriodEnd(end);

        return report;
    }
}
