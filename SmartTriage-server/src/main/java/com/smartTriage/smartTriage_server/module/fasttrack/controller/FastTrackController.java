package com.smartTriage.smartTriage_server.module.fasttrack.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.FastTrackStatus;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.CtResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.EcgResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackActivationRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackOutcomeRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackResponse;
import com.smartTriage.smartTriage_server.module.fasttrack.engine.StrokeMIDetectionEngine;
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
 *
 * Authz note: the mutating endpoints (status / ecg / ct / complete / cancel /
 * acknowledge) are scoped to the activation's own hospital via
 * {@code @clinicalAuthz.canAccessFastTrack} — role-only guarding left a
 * cross-tenant write hole (a clinician at hospital B could record an ECG/CT or
 * drive the status of hospital A's activation by enumerating a UUID).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fast-track")
@RequiredArgsConstructor
public class FastTrackController {

    private final FastTrackService fastTrackService;

    @PostMapping("/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #request.visitId)")
    public ResponseEntity<ApiResponse<FastTrackResponse>> activateFastTrack(
            @Valid @RequestBody FastTrackActivationRequest request) {
        FastTrackResponse response = FastTrackMapper.toResponse(
                fastTrackService.activateFastTrack(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Fast-track protocol activated", response));
    }

    /** Most recent fast-track for a visit, or {@code null} when none exists (no 404). */
    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<FastTrackResponse>> getFastTrackForVisit(
            @PathVariable UUID visitId) {
        var activation = fastTrackService.getFastTrackOrNull(visitId);
        FastTrackResponse response = activation != null ? FastTrackMapper.toResponse(activation) : null;
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Non-binding decision support — runs the stroke/MI detection engine against
     * the visit's latest triage and returns the higher-confidence recommendation
     * (or {@code null}). Advisory only; never auto-activates a pathway.
     */
    @GetMapping("/visit/{visitId}/recommendation")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<StrokeMIDetectionEngine.FastTrackRecommendation>> getRecommendation(
            @PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(fastTrackService.recommend(visitId)));
    }

    /**
     * Active stroke / STEMI fast-track activations. Optionally filtered by ED
     * zone for on-shift clinicians; full hospital view requires cross-zone
     * authority (admin / CN / shift-lead).
     */
    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId) and "
            + "((#zone != null and @clinicalAuthz.canReceiveZoneAlerts(authentication, #hospitalId, #zone)) "
            + "or (#zone == null and @clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)))")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessFastTrack(authentication, #id)")
    public ResponseEntity<ApiResponse<FastTrackResponse>> updateStatus(
            @PathVariable UUID id,
            @RequestParam FastTrackStatus status) {
        FastTrackResponse response = FastTrackMapper.toResponse(
                fastTrackService.updateStatus(id, status));
        return ResponseEntity.ok(ApiResponse.success("Fast-track status updated", response));
    }

    @PutMapping("/{id}/ecg")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessFastTrack(authentication, #id)")
    public ResponseEntity<ApiResponse<FastTrackResponse>> recordEcg(
            @PathVariable UUID id,
            @Valid @RequestBody EcgResultRequest request) {
        FastTrackResponse response = FastTrackMapper.toResponse(
                fastTrackService.recordEcg(id, request));
        return ResponseEntity.ok(ApiResponse.success("ECG result recorded", response));
    }

    @PutMapping("/{id}/ct")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessFastTrack(authentication, #id)")
    public ResponseEntity<ApiResponse<FastTrackResponse>> recordCt(
            @PathVariable UUID id,
            @Valid @RequestBody CtResultRequest request) {
        FastTrackResponse response = FastTrackMapper.toResponse(
                fastTrackService.recordCt(id, request));
        return ResponseEntity.ok(ApiResponse.success("CT result recorded", response));
    }

    /** Complete the pathway with an outcome note (computes door-to-needle). */
    @PutMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessFastTrack(authentication, #id)")
    public ResponseEntity<ApiResponse<FastTrackResponse>> complete(
            @PathVariable UUID id,
            @RequestBody(required = false) FastTrackOutcomeRequest request) {
        String outcome = request != null ? request.getOutcome() : null;
        FastTrackResponse response = FastTrackMapper.toResponse(fastTrackService.complete(id, outcome));
        return ResponseEntity.ok(ApiResponse.success("Fast-track completed", response));
    }

    /** Cancel the pathway (activated in error / ruled out). */
    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessFastTrack(authentication, #id)")
    public ResponseEntity<ApiResponse<FastTrackResponse>> cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) FastTrackOutcomeRequest request) {
        String reason = request != null ? request.getReason() : null;
        FastTrackResponse response = FastTrackMapper.toResponse(fastTrackService.cancel(id, reason));
        return ResponseEntity.ok(ApiResponse.success("Fast-track cancelled", response));
    }

    /** Accept ownership of the door-to-treatment clock. */
    @PutMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessFastTrack(authentication, #id)")
    public ResponseEntity<ApiResponse<FastTrackResponse>> acknowledge(@PathVariable UUID id) {
        FastTrackResponse response = FastTrackMapper.toResponse(fastTrackService.acknowledge(id));
        return ResponseEntity.ok(ApiResponse.success("Fast-track acknowledged", response));
    }
}
