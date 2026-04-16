package com.smartTriage.smartTriage_server.module.visit.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.visit.dto.CreateVisitRequest;
import com.smartTriage.smartTriage_server.module.visit.dto.DispositionRequest;
import com.smartTriage.smartTriage_server.module.visit.dto.VisitResponse;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Visit management endpoints.
 * Visits represent ED encounters — the central workflow record.
 */
@RestController
@RequestMapping("/api/v1/visits")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR', 'NURSE', 'TRIAGE_NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<VisitResponse>> createVisit(
            @Valid @RequestBody CreateVisitRequest request) {
        VisitResponse response = visitService.createVisit(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Visit created successfully", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<VisitResponse>> getVisit(@PathVariable UUID id) {
        VisitResponse response = visitService.getVisitById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/active")
    public ResponseEntity<ApiResponse<Page<VisitResponse>>> getActiveVisits(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<VisitResponse> response = visitService.getActiveVisits(hospitalId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/patient/{patientId}")
    public ResponseEntity<ApiResponse<Page<VisitResponse>>> getVisitsByPatient(
            @PathVariable UUID patientId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<VisitResponse> response = visitService.getVisitsByPatient(patientId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/status/{status}")
    public ResponseEntity<ApiResponse<Page<VisitResponse>>> getVisitsByStatus(
            @PathVariable UUID hospitalId,
            @PathVariable VisitStatus status,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<VisitResponse> response = visitService.getVisitsByStatus(hospitalId, status, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<VisitResponse>> updateVisitStatus(
            @PathVariable UUID id,
            @RequestParam VisitStatus status) {
        VisitResponse response = visitService.updateVisitStatus(id, status);
        return ResponseEntity.ok(ApiResponse.success("Visit status updated", response));
    }

    // ====================================================================
    // ZONE-BASED QUERIES ("My Patients")
    // ====================================================================

    /**
     * Get active visits by ED zone — returns patients whose triage category maps to
     * this zone.
     * Used by doctors to see only patients assigned to their zone.
     */
    @GetMapping("/hospital/{hospitalId}/zone/{zone}")
    public ResponseEntity<ApiResponse<List<VisitResponse>>> getVisitsByZone(
            @PathVariable UUID hospitalId,
            @PathVariable EdZone zone) {
        List<VisitResponse> response = visitService.getVisitsByZone(hospitalId, zone);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ====================================================================
    // DISPOSITION
    // ====================================================================

    /**
     * Record patient disposition — the final step of an ED visit.
     * Automatically stops IoT monitoring and transitions visit status.
     */
    @PostMapping("/{id}/disposition")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<VisitResponse>> recordDisposition(
            @PathVariable UUID id,
            @Valid @RequestBody DispositionRequest request) {
        VisitResponse response = visitService.recordDisposition(id, request);
        return ResponseEntity.ok(ApiResponse.success("Disposition recorded", response));
    }
}
