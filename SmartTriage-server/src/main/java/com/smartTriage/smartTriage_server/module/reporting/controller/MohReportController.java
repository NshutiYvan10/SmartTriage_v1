package com.smartTriage.smartTriage_server.module.reporting.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.reporting.dto.GenerateReportRequest;
import com.smartTriage.smartTriage_server.module.reporting.dto.MohReportResponse;
import com.smartTriage.smartTriage_server.module.reporting.dto.RejectReportRequest;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.reporting.mapper.MohReportMapper;
import com.smartTriage.smartTriage_server.module.reporting.service.MohReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for Rwanda MoH report generation, submission, and review.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/moh-reports")
@RequiredArgsConstructor
public class MohReportController {

    private final MohReportService mohReportService;

    /**
     * Generate a new MoH report.
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<MohReportResponse>> generateReport(
            @Valid @RequestBody GenerateReportRequest request) {
        log.info("Generating {} report for hospital {}", request.getReportType(), request.getHospitalId());

        MohReport report = mohReportService.generateReport(
                request.getHospitalId(),
                request.getReportType(),
                request.getPeriodStart(),
                request.getPeriodEnd());

        return ResponseEntity.ok(ApiResponse.success(
                "Report generated successfully", MohReportMapper.toResponse(report)));
    }

    /**
     * Submit a report for MoH review.
     */
    @PutMapping("/{id}/submit")
    public ResponseEntity<ApiResponse<MohReportResponse>> submitReport(@PathVariable UUID id) {
        log.info("Submitting report {}", id);
        MohReport report = mohReportService.submitReport(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Report submitted for review", MohReportMapper.toResponse(report)));
    }

    /**
     * Accept a submitted report.
     */
    @PutMapping("/{id}/accept")
    public ResponseEntity<ApiResponse<MohReportResponse>> acceptReport(@PathVariable UUID id) {
        log.info("Accepting report {}", id);
        MohReport report = mohReportService.acceptReport(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Report accepted", MohReportMapper.toResponse(report)));
    }

    /**
     * Reject a submitted report with a reason.
     */
    @PutMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<MohReportResponse>> rejectReport(
            @PathVariable UUID id,
            @Valid @RequestBody RejectReportRequest request) {
        log.info("Rejecting report {}", id);
        MohReport report = mohReportService.rejectReport(id, request.getReason());
        return ResponseEntity.ok(ApiResponse.success(
                "Report rejected", MohReportMapper.toResponse(report)));
    }

    /**
     * List all reports for a hospital with pagination.
     */
    @GetMapping("/hospital/{hospitalId}")
    public ResponseEntity<ApiResponse<Page<MohReportResponse>>> getReportsForHospital(
            @PathVariable UUID hospitalId, Pageable pageable) {
        Page<MohReportResponse> reports = mohReportService
                .getReportsForHospital(hospitalId, pageable)
                .map(MohReportMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(reports));
    }

    /**
     * Get a single report by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MohReportResponse>> getReport(@PathVariable UUID id) {
        MohReport report = mohReportService.getReport(id);
        return ResponseEntity.ok(ApiResponse.success(MohReportMapper.toResponse(report)));
    }
}
