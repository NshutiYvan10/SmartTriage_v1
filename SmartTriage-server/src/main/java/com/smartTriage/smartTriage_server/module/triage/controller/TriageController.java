package com.smartTriage.smartTriage_server.module.triage.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.triage.dto.PerformTriageRequest;
import com.smartTriage.smartTriage_server.module.triage.dto.TriageRecordResponse;
import com.smartTriage.smartTriage_server.module.triage.service.TriageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Triage endpoints — the clinical decision interface.
 * Triage operations are restricted to clinical staff.
 */
@RestController
@RequestMapping("/api/v1/triage")
@RequiredArgsConstructor
public class TriageController {

    private final TriageService triageService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<TriageRecordResponse>> performTriage(
            @Valid @RequestBody PerformTriageRequest request) {
        TriageRecordResponse response = triageService.performTriage(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Triage completed", response));
    }

    @GetMapping("/visit/{visitId}/history")
    public ResponseEntity<ApiResponse<Page<TriageRecordResponse>>> getTriageHistory(
            @PathVariable UUID visitId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<TriageRecordResponse> response = triageService.getTriageHistory(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/latest")
    public ResponseEntity<ApiResponse<TriageRecordResponse>> getLatestTriage(
            @PathVariable UUID visitId) {
        TriageRecordResponse response = triageService.getLatestTriage(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
