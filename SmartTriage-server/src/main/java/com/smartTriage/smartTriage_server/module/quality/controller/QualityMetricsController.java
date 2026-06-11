package com.smartTriage.smartTriage_server.module.quality.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.MetricPeriod;
import com.smartTriage.smartTriage_server.module.quality.dto.QualityMetricSnapshotResponse;
import com.smartTriage.smartTriage_server.module.quality.dto.RealTimeMetricsResponse;
import com.smartTriage.smartTriage_server.module.quality.dto.TrendDataResponse;
import com.smartTriage.smartTriage_server.module.quality.entity.QualityMetricSnapshot;
import com.smartTriage.smartTriage_server.module.quality.mapper.QualityMetricSnapshotMapper;
import com.smartTriage.smartTriage_server.module.quality.service.QualityMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<RealTimeMetricsResponse>> getRealTimeMetrics(
            @PathVariable UUID hospitalId) {
        RealTimeMetricsResponse metrics = qualityMetricsService.getRealTimeMetrics(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(metrics));
    }

    /**
     * Get daily metrics snapshot for a specific date.
     */
    @GetMapping("/hospital/{hospitalId}/date/{date}")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
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
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
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
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
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
     * Manually trigger metric computation for a specific date.
     */
    @PostMapping("/hospital/{hospitalId}/compute/{date}")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<QualityMetricSnapshotResponse>> computeMetrics(
            @PathVariable UUID hospitalId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        QualityMetricSnapshot snapshot = qualityMetricsService.computeDailyMetrics(hospitalId, date);
        return ResponseEntity.ok(ApiResponse.success(
                "Metrics computed for " + date, QualityMetricSnapshotMapper.toResponse(snapshot)));
    }
}
