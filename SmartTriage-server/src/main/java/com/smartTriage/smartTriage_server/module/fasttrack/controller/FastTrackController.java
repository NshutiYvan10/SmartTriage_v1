package com.smartTriage.smartTriage_server.module.fasttrack.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.FastTrackStatus;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.CtResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.EcgResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackActivationRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackResponse;
import com.smartTriage.smartTriage_server.module.fasttrack.mapper.FastTrackMapper;
import com.smartTriage.smartTriage_server.module.fasttrack.service.FastTrackService;
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
 * FastTrackController — endpoints for stroke and MI fast-track protocol management.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fast-track")
@RequiredArgsConstructor
public class FastTrackController {

    private final FastTrackService fastTrackService;

    @PostMapping("/activate")
    public ResponseEntity<ApiResponse<FastTrackResponse>> activateFastTrack(
            @Valid @RequestBody FastTrackActivationRequest request) {
        FastTrackResponse response = FastTrackMapper.toResponse(
                fastTrackService.activateFastTrack(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Fast-track protocol activated", response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<FastTrackResponse>> getFastTrackForVisit(
            @PathVariable UUID visitId) {
        FastTrackResponse response = FastTrackMapper.toResponse(
                fastTrackService.getFastTrack(visitId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Active stroke / STEMI fast-track activations. Optionally
     * filtered by ED zone for on-shift clinicians; full hospital view
     * still requires cross-zone authority (admin / CN / shift-lead).
     */
    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId) and "
            + "(#zone != null or @clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId))")
    public ResponseEntity<ApiResponse<List<FastTrackResponse>>> getActiveFastTracks(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) com.smartTriage.smartTriage_server.common.enums.EdZone zone) {
        List<FastTrackResponse> responses = fastTrackService.getActiveFastTracks(hospitalId, zone)
                .stream()
                .map(FastTrackMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<FastTrackResponse>> updateStatus(
            @PathVariable UUID id,
            @RequestParam FastTrackStatus status) {
        FastTrackResponse response = FastTrackMapper.toResponse(
                fastTrackService.updateStatus(id, status));
        return ResponseEntity.ok(ApiResponse.success("Fast-track status updated", response));
    }

    @PutMapping("/{id}/ecg")
    public ResponseEntity<ApiResponse<FastTrackResponse>> recordEcg(
            @PathVariable UUID id,
            @Valid @RequestBody EcgResultRequest request) {
        FastTrackResponse response = FastTrackMapper.toResponse(
                fastTrackService.recordEcg(id, request));
        return ResponseEntity.ok(ApiResponse.success("ECG result recorded", response));
    }

    @PutMapping("/{id}/ct")
    public ResponseEntity<ApiResponse<FastTrackResponse>> recordCt(
            @PathVariable UUID id,
            @Valid @RequestBody CtResultRequest request) {
        FastTrackResponse response = FastTrackMapper.toResponse(
                fastTrackService.recordCt(id, request));
        return ResponseEntity.ok(ApiResponse.success("CT result recorded", response));
    }
}
