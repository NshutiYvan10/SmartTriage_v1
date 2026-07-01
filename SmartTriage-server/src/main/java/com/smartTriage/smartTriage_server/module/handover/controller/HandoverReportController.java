package com.smartTriage.smartTriage_server.module.handover.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.handover.dto.AcknowledgeHandoverRequest;
import com.smartTriage.smartTriage_server.module.handover.dto.GenerateHandoverRequest;
import com.smartTriage.smartTriage_server.module.handover.dto.GenerateShiftHandoverRequest;
import com.smartTriage.smartTriage_server.module.handover.dto.HandoverReportResponse;
import com.smartTriage.smartTriage_server.module.handover.service.HandoverPdfService;
import com.smartTriage.smartTriage_server.module.handover.service.HandoverReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Handover report endpoints — generate and manage patient handover reports
 * for shift changes, ward transfers, discharge summaries, and inter-hospital transfers.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/handover")
@RequiredArgsConstructor
public class HandoverReportController {

    private final HandoverReportService handoverReportService;
    private final HandoverPdfService handoverPdfService;

    /**
     * Generate a handover report for a specific visit.
     */
    @PostMapping("/generate/{visitId}")
    // Authz sweep — a handover report IS the patient summary: visit scope.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'PARAMEDIC') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<HandoverReportResponse>> generateReport(
            @PathVariable UUID visitId,
            @Valid @RequestBody GenerateHandoverRequest request) {
        HandoverReportResponse response = handoverReportService.generateReportResponse(
                visitId,
                request.getReportType(),
                request.getGeneratedByName(),
                request.getNotes()
        );
        return ResponseEntity.ok(ApiResponse.success("Handover report generated", response));
    }

    /**
     * Generate handover reports for ALL active patients at a hospital (bulk shift handover).
     */
    @PostMapping("/generate-bulk/{hospitalId}")
    // Bulk shift handover = every active patient's summary → cross-zone authority.
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<HandoverReportResponse>>> generateBulkShiftHandover(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody GenerateShiftHandoverRequest request) {
        List<HandoverReportResponse> responses =
                handoverReportService.generateBulkShiftHandoverResponses(hospitalId, request);
        return ResponseEntity.ok(ApiResponse.success(
                "Bulk shift handover generated: " + responses.size() + " reports", responses));
    }

    /**
     * Acknowledge receipt of a handover report.
     */
    @PutMapping("/{id}/acknowledge")
    @PreAuthorize("@clinicalAuthz.canReadHandoverReport(authentication, #id)")
    public ResponseEntity<ApiResponse<HandoverReportResponse>> acknowledgeHandover(
            @PathVariable UUID id,
            @Valid @RequestBody AcknowledgeHandoverRequest request) {
        HandoverReportResponse response =
                handoverReportService.acknowledgeHandoverResponse(id, request.getReceiverName());
        return ResponseEntity.ok(ApiResponse.success("Handover acknowledged", response));
    }

    /**
     * Get all handover reports for a visit.
     */
    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<HandoverReportResponse>>> getReportsForVisit(
            @PathVariable UUID visitId) {
        List<HandoverReportResponse> responses = handoverReportService.getReportResponsesForVisit(visitId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    /**
     * Get handover reports generated during the current/specified shift.
     * Defaults to last 12 hours if no parameters provided.
     */
    @GetMapping("/hospital/{hospitalId}/shift")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<HandoverReportResponse>>> getReportsForShift(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) Instant shiftStart,
            @RequestParam(required = false) Instant shiftEnd) {
        if (shiftStart == null) {
            shiftStart = Instant.now().minus(12, ChronoUnit.HOURS);
        }
        if (shiftEnd == null) {
            shiftEnd = Instant.now();
        }
        List<HandoverReportResponse> responses =
                handoverReportService.getReportResponsesForShift(hospitalId, shiftStart, shiftEnd);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    /**
     * Get a single handover report by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("@clinicalAuthz.canReadHandoverReport(authentication, #id)")
    public ResponseEntity<ApiResponse<HandoverReportResponse>> getReport(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(handoverReportService.getReportResponse(id)));
    }

    /**
     * Download the handover as a professional, letterheaded PDF for printing /
     * physical record-keeping. Contains every on-screen section verbatim.
     */
    @GetMapping("/{id}/pdf")
    @PreAuthorize("@clinicalAuthz.canReadHandoverReport(authentication, #id)")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable UUID id,
            org.springframework.security.core.Authentication authentication) {
        String exportedBy = "SmartTriage user";
        if (authentication != null
                && authentication.getPrincipal() instanceof com.smartTriage.smartTriage_server.module.user.entity.User u) {
            exportedBy = (u.getFirstName() + " " + u.getLastName()).trim();
            if (exportedBy.isBlank()) exportedBy = u.getEmail();
        }
        HandoverPdfService.RenderedPdf pdf = handoverPdfService.renderDocument(id, exportedBy);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.filename() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.bytes());
    }
}
