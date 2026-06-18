package com.smartTriage.smartTriage_server.module.referral.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.referral.dto.CreateReferralRequest;
import com.smartTriage.smartTriage_server.module.referral.dto.ReferralResponse;
import com.smartTriage.smartTriage_server.module.referral.dto.RespondReferralRequest;
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

/**
 * Referral / consultation endpoints. Request + response derive the actor from
 * the authenticated principal and are hospital-scoped (canAccessVisit /
 * canAccessReferral).
 *
 *   POST /api/v1/referrals/visit/{visitId} → raise a referral / consult
 *   PUT  /api/v1/referrals/{id}/respond     → consultant accept / decline / complete
 *   PUT  /api/v1/referrals/{id}/cancel      → requester cancels
 *   GET  /api/v1/referrals/visit/{visitId}  → referrals for a visit
 *   GET  /api/v1/referrals/{id}             → single referral
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/referrals")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;

    @PostMapping("/visit/{visitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<ReferralResponse>> requestReferral(
            @PathVariable UUID visitId,
            @Valid @RequestBody CreateReferralRequest request) {
        ReferralResponse response = referralService.requestReferral(visitId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Referral requested", response));
    }

    @PutMapping("/{id}/respond")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessReferral(authentication, #id)")
    public ResponseEntity<ApiResponse<ReferralResponse>> respondToReferral(
            @PathVariable UUID id,
            @Valid @RequestBody RespondReferralRequest request) {
        ReferralResponse response = referralService.respondToReferral(id, request);
        return ResponseEntity.ok(ApiResponse.success("Referral response recorded", response));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessReferral(authentication, #id)")
    public ResponseEntity<ApiResponse<ReferralResponse>> cancelReferral(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Referral cancelled", referralService.cancelReferral(id)));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<ReferralResponse>>> getReferralsForVisit(@PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(referralService.getReferralsForVisit(visitId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@clinicalAuthz.canAccessReferral(authentication, #id)")
    public ResponseEntity<ApiResponse<ReferralResponse>> getReferral(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(referralService.getReferral(id)));
    }
}
