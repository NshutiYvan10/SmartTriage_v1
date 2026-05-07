package com.smartTriage.smartTriage_server.module.sepsis.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.SepsisBundleItem;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisBundleStatusResponse;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisScreeningRequest;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisScreeningResponse;
import com.smartTriage.smartTriage_server.module.sepsis.entity.SepsisScreening;
import com.smartTriage.smartTriage_server.module.sepsis.mapper.SepsisMapper;
import com.smartTriage.smartTriage_server.module.sepsis.service.SepsisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SepsisController — endpoints for sepsis screening, bundle management,
 * and the sepsis dashboard.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sepsis")
@RequiredArgsConstructor
public class SepsisController {

    private final SepsisService sepsisService;

    /**
     * Trigger a sepsis screening for a visit using the latest vital signs.
     */
    @PostMapping("/screen/{visitId}")
    public ResponseEntity<ApiResponse<SepsisScreeningResponse>> screenPatient(
            @PathVariable UUID visitId,
            @RequestBody(required = false) SepsisScreeningRequest request) {
        SepsisScreening screening = sepsisService.screenPatient(visitId, request);
        SepsisScreeningResponse response = SepsisMapper.toResponse(screening);
        return ResponseEntity.ok(ApiResponse.success("Sepsis screening completed", response));
    }

    /**
     * Get screening history for a visit.
     */
    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<SepsisScreeningResponse>>> getScreenings(
            @PathVariable UUID visitId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<SepsisScreeningResponse> response = sepsisService.getScreenings(visitId, pageable)
                .map(SepsisMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get the current active screening for a visit.
     */
    @GetMapping("/visit/{visitId}/active")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<SepsisScreeningResponse>> getActiveScreening(
            @PathVariable UUID visitId) {
        SepsisScreening screening = sepsisService.getActiveScreening(visitId);
        SepsisScreeningResponse response = SepsisMapper.toResponse(screening);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Start the 1-hour sepsis bundle timer for a screening.
     */
    @PutMapping("/bundle/{screeningId}/start")
    public ResponseEntity<ApiResponse<SepsisBundleStatusResponse>> startBundle(
            @PathVariable UUID screeningId) {
        SepsisScreening screening = sepsisService.startBundle(screeningId);
        SepsisBundleStatusResponse response = SepsisMapper.toBundleStatusResponse(screening);
        return ResponseEntity.ok(ApiResponse.success("Sepsis bundle timer started", response));
    }

    /**
     * Complete a specific bundle item.
     */
    @PutMapping("/bundle/{screeningId}/item/{item}")
    public ResponseEntity<ApiResponse<SepsisBundleStatusResponse>> completeBundleItem(
            @PathVariable UUID screeningId,
            @PathVariable SepsisBundleItem item) {
        SepsisScreening screening = sepsisService.completeBundleItem(screeningId, item);
        SepsisBundleStatusResponse response = SepsisMapper.toBundleStatusResponse(screening);
        return ResponseEntity.ok(ApiResponse.success("Bundle item completed: " + item.name(), response));
    }

    /**
     * Get all active sepsis cases at a hospital.
     */
    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<SepsisScreeningResponse>>> getActiveSepsisCases(
            @PathVariable UUID hospitalId) {
        List<SepsisScreeningResponse> response = sepsisService.getActiveSepsisCases(hospitalId)
                .stream()
                .map(SepsisMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(
                "Found " + response.size() + " active sepsis cases", response));
    }
}
