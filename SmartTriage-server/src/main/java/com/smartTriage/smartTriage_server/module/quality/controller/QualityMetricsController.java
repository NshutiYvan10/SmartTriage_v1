package com.smartTriage.smartTriage_server.module.quality.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.MetricPeriod;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.quality.dto.GenerateSnapshotRequest;
import com.smartTriage.smartTriage_server.module.quality.dto.QualityMetricSnapshotResponse;
import com.smartTriage.smartTriage_server.module.quality.dto.RealTimeMetricsResponse;
import com.smartTriage.smartTriage_server.module.quality.dto.TrendDataResponse;
import com.smartTriage.smartTriage_server.module.quality.entity.QualityMetricSnapshot;
import com.smartTriage.smartTriage_server.module.quality.mapper.QualityMetricSnapshotMapper;
import com.smartTriage.smartTriage_server.module.quality.service.QualityMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Quality metrics endpoints — dashboards, trend analysis, and metric computation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/quality")
@RequiredArgsConstructor
public class QualityMetricsController {

    private final QualityMetricsService qualityMetricsService;

    /**
     * Get live real-time metrics from active visits (not persisted).
     */
    @GetMapping("/hospital/{hospitalId}/realtime")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<RealTimeMetricsResponse>> getRealTimeMetrics(
            @PathVariable UUID hospitalId) {
        RealTimeMetricsResponse metrics = qualityMetricsService.getRealTimeMetrics(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(metrics));
    }

    /**
     * Get daily metrics snapshot for a specific date.
     */
    @GetMapping("/hospital/{hospitalId}/date/{date}")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<QualityMetricSnapshotResponse>> getMetricsByDate(
            @PathVariable UUID hospitalId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        QualityMetricSnapshot snapshot = qualityMetricsService.getMetricsByDate(hospitalId, date);
        return ResponseEntity.ok(ApiResponse.success(QualityMetricSnapshotMapper.toResponse(snapshot)));
    }

    /**
     * Get metrics for a date range.
     */
    @GetMapping("/hospital/{hospitalId}/range")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<QualityMetricSnapshotResponse>>> getMetricsByRange(
            @PathVariable UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<QualityMetricSnapshotResponse> responses = qualityMetricsService
                .getMetricsByRange(hospitalId, from, to)
                .stream()
                .map(QualityMetricSnapshotMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    /**
     * Get trend data — last N periods for a specific metric period.
     */
    @GetMapping("/hospital/{hospitalId}/trends")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<TrendDataResponse>> getTrends(
            @PathVariable UUID hospitalId,
            @RequestParam(defaultValue = "DAILY") MetricPeriod period,
            @RequestParam(defaultValue = "30") int count) {
        List<QualityMetricSnapshot> snapshots = qualityMetricsService.getTrends(hospitalId, period, count);
        List<QualityMetricSnapshotResponse> dataPoints = snapshots.stream()
                .map(QualityMetricSnapshotMapper::toResponse)
                .collect(Collectors.toList());

        String hospitalName = dataPoints.isEmpty() ? "" : dataPoints.get(0).getHospitalName();

        TrendDataResponse trend = TrendDataResponse.builder()
                .hospitalId(hospitalId)
                .hospitalName(hospitalName)
                .period(period)
                .dataPointCount(dataPoints.size())
                .dataPoints(dataPoints)
                .build();

        return ResponseEntity.ok(ApiResponse.success(trend));
    }

    /**
     * Export the hospital's quality-metric snapshots over a date range as CSV — one row per
     * snapshot, every metric column. Governance/admin reporting; same read gate as the dashboard.
     */
    @GetMapping("/hospital/{hospitalId}/export/csv")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId)")
    public ResponseEntity<String> exportCsv(
            @PathVariable UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<QualityMetricSnapshot> rows = qualityMetricsService.getMetricsByRange(hospitalId, from, to);
        StringBuilder sb = new StringBuilder(
                "Date,Period,TotalPatients,Admissions,Discharges,Transfers,Deaths,LWBS,Pediatric,"
                + "Red,Orange,Yellow,Green,Blue,AvgTEWS,AvgWaitMin,DoorToTriageMin,DoorToPhysicianMin,"
                + "TotalEdStayMin,PctSeenWithinTarget,SepsisScreeningRate,SepsisBundleCompliance,"
                + "CriticalLabTurnaroundMin,MedicationErrors,SafetyIncidents,PeakOccupancy,AvgOccupancy,"
                + "IcuUtilizationPct,EdUtilizationPct,EdMortalityRatePct,MortalityWithin24h\n");
        for (QualityMetricSnapshot m : rows) {
            sb.append(csv(m.getSnapshotDate())).append(',').append(csv(m.getSnapshotPeriod())).append(',')
              .append(csv(m.getTotalPatients())).append(',').append(csv(m.getTotalAdmissions())).append(',')
              .append(csv(m.getTotalDischarges())).append(',').append(csv(m.getTotalTransfers())).append(',')
              .append(csv(m.getTotalDeaths())).append(',').append(csv(m.getTotalLeftWithoutBeingSeen())).append(',')
              .append(csv(m.getPediatricPatients())).append(',')
              .append(csv(m.getRedPatients())).append(',').append(csv(m.getOrangePatients())).append(',')
              .append(csv(m.getYellowPatients())).append(',').append(csv(m.getGreenPatients())).append(',')
              .append(csv(m.getBluePatients())).append(',').append(csv(m.getAverageTewsScore())).append(',')
              .append(csv(m.getAverageWaitTimeMinutes())).append(',').append(csv(m.getAverageDoorToTriageMinutes())).append(',')
              .append(csv(m.getAverageDoorToPhysicianMinutes())).append(',').append(csv(m.getAverageTotalEdStayMinutes())).append(',')
              .append(csv(m.getPercentSeenWithinTarget())).append(',').append(csv(m.getSepsisScreeningRate())).append(',')
              .append(csv(m.getSepsisBundleComplianceRate())).append(',').append(csv(m.getCriticalLabTurnaroundMinutes())).append(',')
              .append(csv(m.getMedicationErrorCount())).append(',').append(csv(m.getSafetyIncidentCount())).append(',')
              .append(csv(m.getPeakEdOccupancy())).append(',').append(csv(m.getAverageEdOccupancy())).append(',')
              .append(csv(m.getIcuBedUtilizationPercent())).append(',').append(csv(m.getEdBedUtilizationPercent())).append(',')
              .append(csv(m.getEdMortalityRate())).append(',').append(csv(m.getMortalityWithin24Hours())).append('\n');
        }
        String filename = "quality-metrics_" + from + "_" + to + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(sb.toString());
    }

    /** CSV-escape a cell: quote when it contains a comma, quote, or newline; blank for null. */
    private static String csv(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * Manually trigger metric computation for a specific date.
     */
    @PostMapping("/hospital/{hospitalId}/compute/{date}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN') and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<QualityMetricSnapshotResponse>> computeMetrics(
            @PathVariable UUID hospitalId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        QualityMetricSnapshot snapshot = qualityMetricsService.computeDailyMetrics(hospitalId, date);
        return ResponseEntity.ok(ApiResponse.success(
                "Metrics computed for " + date, QualityMetricSnapshotMapper.toResponse(snapshot)));
    }

    /**
     * Latest persisted snapshot of any period — the dashboard's default view.
     * 200 with {@code null} data when the hospital has no snapshots yet (the
     * dashboard renders its empty state and offers Generate).
     */
    @GetMapping("/hospital/{hospitalId}/latest")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<QualityMetricSnapshotResponse>> getLatest(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(
                qualityMetricsService.getLatestSnapshot(hospitalId).orElse(null)));
    }

    /**
     * Paged snapshot history, newest first — the dashboard uses this to find
     * the previous snapshot of the same period for delta comparisons.
     */
    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<QualityMetricSnapshotResponse>>> getHistory(
            @PathVariable UUID hospitalId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                qualityMetricsService.getSnapshotHistory(hospitalId,
                        org.springframework.data.domain.PageRequest.of(page, Math.min(size, 100)))));
    }

    /**
     * Generate a snapshot for the requested period, anchored on {@code date}:
     * DAILY computes that day; WEEKLY the Monday-anchored week containing it;
     * MONTHLY its calendar month (aggregates roll up the DAILY snapshots).
     * Matches the dashboard's Generate Snapshot button.
     */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #request.hospitalId)")
    public ResponseEntity<ApiResponse<QualityMetricSnapshotResponse>> generateSnapshot(
            @Valid @RequestBody GenerateSnapshotRequest request) {
        MetricPeriod period = request.getPeriod() != null ? request.getPeriod() : MetricPeriod.DAILY;
        LocalDate date = request.getDate();
        QualityMetricSnapshot snapshot = switch (period) {
            case DAILY -> qualityMetricsService.computeDailyMetrics(request.getHospitalId(), date);
            case WEEKLY -> qualityMetricsService.computeWeeklyMetrics(
                    request.getHospitalId(), date.with(DayOfWeek.MONDAY));
            case MONTHLY -> qualityMetricsService.computeMonthlyMetrics(
                    request.getHospitalId(), YearMonth.from(date));
            default -> throw new ClinicalBusinessException(
                    "Snapshot period " + period + " is not supported for manual generation. "
                    + "Use DAILY, WEEKLY, or MONTHLY.");
        };
        return ResponseEntity.ok(ApiResponse.success(
                period + " metrics computed for " + date,
                QualityMetricSnapshotMapper.toResponse(snapshot)));
    }
}
