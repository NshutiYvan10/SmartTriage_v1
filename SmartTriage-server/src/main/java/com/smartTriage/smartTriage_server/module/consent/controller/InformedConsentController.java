package com.smartTriage.smartTriage_server.module.consent.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.consent.dto.ConsentResponse;
import com.smartTriage.smartTriage_server.module.consent.dto.RecordConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.dto.WithdrawConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.service.InformedConsentService;
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
 * Informed-consent endpoints. All writes derive the clinician from the
 * authenticated principal and are hospital-scoped (canAccessVisit / canAccessConsent).
 *
 *   POST /api/v1/consents/visit/{visitId}   → record consent (GIVEN / REFUSED)
 *   PUT  /api/v1/consents/{id}/withdraw      → withdraw a GIVEN consent
 *   GET  /api/v1/consents/visit/{visitId}    → consents for a visit
 *   GET  /api/v1/consents/{id}               → single consent
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/consents")
@RequiredArgsConstructor
public class InformedConsentController {

    private final InformedConsentService consentService;

    @PostMapping("/visit/{visitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<ConsentResponse>> recordConsent(
            @PathVariable UUID visitId,
            @Valid @RequestBody RecordConsentRequest request) {
        ConsentResponse response = consentService.recordConsent(visitId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Informed consent recorded", response));
    }

    @PutMapping("/{id}/withdraw")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessConsent(authentication, #id)")
    public ResponseEntity<ApiResponse<ConsentResponse>> withdrawConsent(
            @PathVariable UUID id,
            @Valid @RequestBody WithdrawConsentRequest request) {
        ConsentResponse response = consentService.withdrawConsent(id, request);
        return ResponseEntity.ok(ApiResponse.success("Consent withdrawn", response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<ConsentResponse>>> getConsentsForVisit(@PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(consentService.getConsentsForVisit(visitId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@clinicalAuthz.canAccessConsent(authentication, #id)")
    public ResponseEntity<ApiResponse<ConsentResponse>> getConsent(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(consentService.getConsent(id)));
    }
}
