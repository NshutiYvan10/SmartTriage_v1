package com.smartTriage.smartTriage_server.module.reporting.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.reporting.dto.GenerateNationalReportRequest;
import com.smartTriage.smartTriage_server.module.reporting.dto.GenerateReportRequest;
import com.smartTriage.smartTriage_server.module.reporting.dto.MohReportResponse;
import com.smartTriage.smartTriage_server.module.reporting.dto.RejectReportRequest;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.reporting.mapper.MohReportMapper;
import com.smartTriage.smartTriage_server.module.reporting.service.MohReportPdfService;
import com.smartTriage.smartTriage_server.module.reporting.service.MohReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for Rwanda MoH report generation, submission, and review.
 *
 * RBAC fix — entire controller is now admin / governance-only. Class-level
 * gate denies all clinical staff; ministry reporting is administrative work.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/moh-reports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'READ_ONLY')")
public class MohReportController {

    private final MohReportService mohReportService;
    private final MohReportPdfService mohReportPdfService;

    /**
     * Generate a new MoH report.
     */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
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
     * Generate a NATIONAL rollup aggregated across all active hospitals. SUPER_ADMIN only —
     * this is the national health-authority view, not a single hospital's report.
     */
    @PostMapping("/national/generate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MohReportResponse>> generateNationalReport(
            @Valid @RequestBody GenerateNationalReportRequest request) {
        log.info("Generating NATIONAL {} report", request.getReportType());

        MohReport report = mohReportService.generateNationalReport(
                request.getReportType(),
                request.getPeriodStart(),
                request.getPeriodEnd());

        return ResponseEntity.ok(ApiResponse.success(
                "National report generated successfully", MohReportMapper.toResponse(report)));
    }

    /**
     * List NATIONAL rollups with pagination. SUPER_ADMIN only.
     */
    @GetMapping("/national")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Page<MohReportResponse>>> getNationalReports(Pageable pageable) {
        Page<MohReportResponse> reports = mohReportService
                .getNationalReports(pageable)
                .map(MohReportMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(reports));
    }

    /**
     * Submit a report for MoH review.
     */
    @PutMapping("/{id}/submit")
    @PreAuthorize("@clinicalAuthz.canSubmitMohReport(authentication, #id)")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
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
    @PreAuthorize("@clinicalAuthz.canViewMohReport(authentication, #id)")
    public ResponseEntity<ApiResponse<MohReportResponse>> getReport(@PathVariable UUID id) {
        MohReport report = mohReportService.getReport(id);
        return ResponseEntity.ok(ApiResponse.success(MohReportMapper.toResponse(report)));
    }

    /**
     * Download a report as a printable / submittable PDF (statutory MoH / HMIS return).
     * De-identified aggregate statistics only; same admin / governance gate as the
     * class. Streamed inline as application/pdf.
     */
    @GetMapping("/{id}/pdf")
    @PreAuthorize("@clinicalAuthz.canViewMohReport(authentication, #id)")
    public ResponseEntity<byte[]> downloadReportPdf(@PathVariable UUID id) {
        byte[] pdf = mohReportPdfService.renderById(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"moh-report-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
