package com.smartTriage.smartTriage_server.module.offline.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.offline.dto.*;
import com.smartTriage.smartTriage_server.module.offline.mapper.OfflineSyncMapper;
import com.smartTriage.smartTriage_server.module.offline.service.OfflineSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OfflineSyncController — endpoints for offline data synchronization.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/offline")
@RequiredArgsConstructor
public class OfflineSyncController {

    private final OfflineSyncService offlineSyncService;

    @PostMapping("/sync")
    @PreAuthorize("isAuthenticated() and !hasRole('READ_ONLY')")
    public ResponseEntity<ApiResponse<SyncBatchResponse>> syncBatch(
            @Valid @RequestBody List<OfflineSyncRequest> records) {
        SyncBatchResponse response = offlineSyncService.syncBatch(records);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Sync batch processed", response));
    }

    @PutMapping("/conflict/{id}/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<OfflineSyncRecordResponse>> resolveConflict(
            @PathVariable UUID id,
            @Valid @RequestBody ConflictResolutionRequest request) {
        OfflineSyncRecordResponse response = OfflineSyncMapper.toResponse(
                offlineSyncService.resolveConflict(id, request));
        return ResponseEntity.ok(ApiResponse.success("Conflict resolved", response));
    }

    @GetMapping("/pending/{clientDeviceId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<OfflineSyncRecordResponse>>> getPendingRecords(
            @PathVariable String clientDeviceId) {
        List<OfflineSyncRecordResponse> responses = offlineSyncService.getPendingRecords(clientDeviceId)
                .stream()
                .map(OfflineSyncMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/conflicts/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<OfflineSyncRecordResponse>>> getConflicts(
            @PathVariable UUID hospitalId) {
        List<OfflineSyncRecordResponse> responses = offlineSyncService.getConflicts(hospitalId)
                .stream()
                .map(OfflineSyncMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/status/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<SyncStatusResponse>> getSyncStatus(
            @PathVariable UUID hospitalId) {
        SyncStatusResponse response = offlineSyncService.getSyncStatus(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
