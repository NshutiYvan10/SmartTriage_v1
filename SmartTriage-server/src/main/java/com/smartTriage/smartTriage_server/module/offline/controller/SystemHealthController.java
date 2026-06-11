package com.smartTriage.smartTriage_server.module.offline.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.offline.dto.HealthCheckRequest;
import com.smartTriage.smartTriage_server.module.offline.dto.SystemHealthResponse;
import com.smartTriage.smartTriage_server.module.offline.mapper.SystemHealthMapper;
import com.smartTriage.smartTriage_server.module.offline.service.SystemHealthService;
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

import java.util.UUID;

/**
 * SystemHealthController — endpoints for system health monitoring.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system-health")
@RequiredArgsConstructor
public class SystemHealthController {

    private final SystemHealthService systemHealthService;

    @PostMapping("/check")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #request.hospitalId)")
    public ResponseEntity<ApiResponse<SystemHealthResponse>> recordHealthCheck(
            @Valid @RequestBody HealthCheckRequest request) {
        SystemHealthResponse response = SystemHealthMapper.toResponse(
                systemHealthService.recordHealthCheck(request.getHospitalId(), request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Health check recorded", response));
    }

    @GetMapping("/{hospitalId}/latest")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<SystemHealthResponse>> getLatestHealth(
            @PathVariable UUID hospitalId) {
        SystemHealthResponse response = SystemHealthMapper.toResponse(
                systemHealthService.getLatestHealth(hospitalId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{hospitalId}/history")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<SystemHealthResponse>>> getHealthHistory(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<SystemHealthResponse> responses = systemHealthService.getHealthHistory(hospitalId, pageable)
                .map(SystemHealthMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
}
