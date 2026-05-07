package com.smartTriage.smartTriage_server.module.referral.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.referral.dto.*;
import com.smartTriage.smartTriage_server.module.referral.mapper.ReferralMapper;
import com.smartTriage.smartTriage_server.module.referral.service.ReferralService;
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
 * ReferralController — endpoints for inter-hospital referral and transfer management.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/referrals")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;

    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<ReferralResponse>> initiateReferral(
            @Valid @RequestBody InitiateReferralRequest request) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.initiateReferral(request.getVisitId(), request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Referral initiated", response));
    }

    @PutMapping("/{id}/contact")
    public ResponseEntity<ApiResponse<ReferralResponse>> contactReceivingFacility(
            @PathVariable UUID id,
            @Valid @RequestBody ContactFacilityRequest request) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.contactReceivingFacility(id, request));
        return ResponseEntity.ok(ApiResponse.success("Receiving facility contacted", response));
    }

    @PutMapping("/{id}/accept")
    public ResponseEntity<ApiResponse<ReferralResponse>> recordAcceptance(
            @PathVariable UUID id,
            @Valid @RequestBody AcceptReferralRequest request) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.recordAcceptance(id, request));
        return ResponseEntity.ok(ApiResponse.success("Referral accepted", response));
    }

    @PutMapping("/{id}/decline")
    public ResponseEntity<ApiResponse<ReferralResponse>> recordDecline(
            @PathVariable UUID id,
            @RequestParam String reason) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.recordDecline(id, reason));
        return ResponseEntity.ok(ApiResponse.success("Referral declined", response));
    }

    @PutMapping("/{id}/stabilize")
    public ResponseEntity<ApiResponse<ReferralResponse>> recordStabilization(
            @PathVariable UUID id) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.recordStabilization(id));
        return ResponseEntity.ok(ApiResponse.success("Patient stabilized", response));
    }

    @PutMapping("/{id}/depart")
    public ResponseEntity<ApiResponse<ReferralResponse>> recordDeparture(
            @PathVariable UUID id,
            @Valid @RequestBody DepartureRequest request) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.recordDeparture(id, request));
        return ResponseEntity.ok(ApiResponse.success("Patient departed", response));
    }

    @PutMapping("/{id}/arrive")
    public ResponseEntity<ApiResponse<ReferralResponse>> recordArrival(
            @PathVariable UUID id) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.recordArrival(id));
        return ResponseEntity.ok(ApiResponse.success("Patient arrived at destination", response));
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<ReferralResponse>> completeReferral(
            @PathVariable UUID id) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.completeReferral(id));
        return ResponseEntity.ok(ApiResponse.success("Referral completed", response));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<ReferralResponse>> cancelReferral(
            @PathVariable UUID id,
            @RequestParam String reason) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.cancelReferral(id, reason));
        return ResponseEntity.ok(ApiResponse.success("Referral cancelled", response));
    }

    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<ReferralResponse>>> getActiveReferrals(
            @PathVariable UUID hospitalId) {
        List<ReferralResponse> responses = referralService.getActiveReferrals(hospitalId)
                .stream()
                .map(ReferralMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<ReferralResponse>> getReferralForVisit(
            @PathVariable UUID visitId) {
        ReferralResponse response = ReferralMapper.toResponse(
                referralService.getReferralForVisit(visitId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}/summary")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ReferralSummaryResponse>> generateReferralSummary(
            @PathVariable UUID id) {
        ReferralSummaryResponse response = referralService.generateReferralSummary(id);
        return ResponseEntity.ok(ApiResponse.success("Referral summary generated", response));
    }
}
