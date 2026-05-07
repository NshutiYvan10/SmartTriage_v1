package com.smartTriage.smartTriage_server.module.handover.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.handover.dto.AcknowledgeHandoverRequest;
import com.smartTriage.smartTriage_server.module.handover.dto.GenerateHandoverRequest;
import com.smartTriage.smartTriage_server.module.handover.dto.GenerateShiftHandoverRequest;
import com.smartTriage.smartTriage_server.module.handover.dto.HandoverReportResponse;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
import com.smartTriage.smartTriage_server.module.handover.mapper.HandoverReportMapper;
import com.smartTriage.smartTriage_server.module.handover.service.HandoverReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * Generate a handover report for a specific visit.
     */
    @PostMapping("/generate/{visitId}")
    public ResponseEntity<ApiResponse<HandoverReportResponse>> generateReport(
            @PathVariable UUID visitId,
            @Valid @RequestBody GenerateHandoverRequest request) {
        HandoverReport report = handoverReportService.generateReport(
                visitId,
                request.getReportType(),
                request.getGeneratedByName(),
                request.getNotes()
        );
        return ResponseEntity.ok(ApiResponse.success(
                "Handover report generated", HandoverReportMapper.toResponse(report)));
    }

    /**
     * Generate handover reports for ALL active patients at a hospital (bulk shift handover).
     */
    @PostMapping("/generate-bulk/{hospitalId}")
    public ResponseEntity<ApiResponse<List<HandoverReportResponse>>> generateBulkShiftHandover(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody GenerateShiftHandoverRequest request) {
        List<HandoverReport> reports = handoverReportService.generateBulkShiftHandover(hospitalId, request);
        List<HandoverReportResponse> responses = reports.stream()
                .map(HandoverReportMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(
                "Bulk shift handover generated: " + responses.size() + " reports", responses));
    }

    /**
     * Acknowledge receipt of a handover report.
     */
    @PutMapping("/{id}/acknowledge")
    public ResponseEntity<ApiResponse<HandoverReportResponse>> acknowledgeHandover(
            @PathVariable UUID id,
            @Valid @RequestBody AcknowledgeHandoverRequest request) {
        HandoverReport report = handoverReportService.acknowledgeHandover(id, request.getReceiverName());
        return ResponseEntity.ok(ApiResponse.success(
                "Handover acknowledged", HandoverReportMapper.toResponse(report)));
    }

    /**
     * Get all handover reports for a visit.
     */
    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<HandoverReportResponse>>> getReportsForVisit(
            @PathVariable UUID visitId) {
        List<HandoverReportResponse> responses = handoverReportService.getReportsForVisit(visitId)
                .stream()
                .map(HandoverReportMapper::toResponse)
                .collect(Collectors.toList());
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
        List<HandoverReportResponse> responses = handoverReportService
                .getReportsForShift(hospitalId, shiftStart, shiftEnd)
                .stream()
                .map(HandoverReportMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    /**
     * Get a single handover report by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<HandoverReportResponse>> getReport(@PathVariable UUID id) {
        HandoverReport report = handoverReportService.getReport(id);
        return ResponseEntity.ok(ApiResponse.success(HandoverReportMapper.toResponse(report)));
    }
}
