package com.smartTriage.smartTriage_server.module.safety.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.IncidentType;
import com.smartTriage.smartTriage_server.module.safety.dto.*;
import com.smartTriage.smartTriage_server.module.safety.mapper.SafetyIncidentMapper;
import com.smartTriage.smartTriage_server.module.safety.service.SafetyIncidentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SafetyIncidentController — endpoints for patient safety incident reporting and management.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/safety/incidents")
@RequiredArgsConstructor
public class SafetyIncidentController {

    private final SafetyIncidentService safetyIncidentService;

    @PostMapping
    // Authz sweep — DELIBERATELY any authenticated staff member: incident
    // reporting must be frictionless (blameless reporting culture).
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SafetyIncidentResponse>> reportIncident(
            @Valid @RequestBody ReportIncidentRequest request) {
        SafetyIncidentResponse response = SafetyIncidentMapper.toResponse(
                safetyIncidentService.reportIncident(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Safety incident reported", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<SafetyIncidentResponse>> updateIncident(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIncidentRequest request) {
        SafetyIncidentResponse response = SafetyIncidentMapper.toResponse(
                safetyIncidentService.updateIncident(id, request));
        return ResponseEntity.ok(ApiResponse.success("Incident updated", response));
    }

    @PutMapping("/{id}/investigate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<SafetyIncidentResponse>> startInvestigation(
            @PathVariable UUID id,
            @RequestParam String investigatorName) {
        SafetyIncidentResponse response = SafetyIncidentMapper.toResponse(
                safetyIncidentService.startInvestigation(id, investigatorName));
        return ResponseEntity.ok(ApiResponse.success("Investigation started", response));
    }

    @PutMapping("/{id}/root-cause")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<SafetyIncidentResponse>> recordRootCause(
            @PathVariable UUID id,
            @Valid @RequestBody RootCauseRequest request) {
        SafetyIncidentResponse response = SafetyIncidentMapper.toResponse(
                safetyIncidentService.recordRootCause(id, request));
        return ResponseEntity.ok(ApiResponse.success("Root cause recorded", response));
    }

    @PutMapping("/{id}/corrective-action")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<SafetyIncidentResponse>> planCorrectiveAction(
            @PathVariable UUID id,
            @Valid @RequestBody CorrectiveActionRequest request) {
        SafetyIncidentResponse response = SafetyIncidentMapper.toResponse(
                safetyIncidentService.planCorrectiveAction(id, request));
        return ResponseEntity.ok(ApiResponse.success("Corrective action planned", response));
    }

    @PutMapping("/{id}/complete-action")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<SafetyIncidentResponse>> completeCorrectiveAction(
            @PathVariable UUID id) {
        SafetyIncidentResponse response = SafetyIncidentMapper.toResponse(
                safetyIncidentService.completeCorrectiveAction(id));
        return ResponseEntity.ok(ApiResponse.success("Corrective action completed", response));
    }

    @PutMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<SafetyIncidentResponse>> closeIncident(
            @PathVariable UUID id,
            @Valid @RequestBody CloseIncidentRequest request) {
        SafetyIncidentResponse response = SafetyIncidentMapper.toResponse(
                safetyIncidentService.closeIncident(id, request));
        return ResponseEntity.ok(ApiResponse.success("Incident closed", response));
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<SafetyIncidentResponse>>> getIncidentsByHospital(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) IncidentType type,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<SafetyIncidentResponse> responses;
        if (type != null) {
            responses = safetyIncidentService.getIncidentsByType(hospitalId, type, pageable)
                    .map(SafetyIncidentMapper::toResponse);
        } else {
            responses = safetyIncidentService.getIncidentsByHospital(hospitalId, pageable)
                    .map(SafetyIncidentMapper::toResponse);
        }
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/hospital/{hospitalId}/open")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<SafetyIncidentResponse>>> getOpenIncidents(
            @PathVariable UUID hospitalId) {
        List<SafetyIncidentResponse> responses = safetyIncidentService.getOpenIncidents(hospitalId)
                .stream()
                .map(SafetyIncidentMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/hospital/{hospitalId}/stats")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<IncidentStatsResponse>> getIncidentStats(
            @PathVariable UUID hospitalId,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        IncidentStatsResponse response = safetyIncidentService.getIncidentStats(hospitalId, from, to);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SafetyIncidentResponse>> getIncident(
            @PathVariable UUID id) {
        SafetyIncidentResponse response = SafetyIncidentMapper.toResponse(
                safetyIncidentService.getIncident(id));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
