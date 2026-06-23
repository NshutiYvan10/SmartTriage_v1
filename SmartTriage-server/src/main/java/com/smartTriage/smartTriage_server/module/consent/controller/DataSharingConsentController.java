package com.smartTriage.smartTriage_server.module.consent.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.consent.dto.DataSharingConsentResponse;
import com.smartTriage.smartTriage_server.module.consent.dto.RecordDataSharingConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.dto.WithdrawConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.service.DataSharingConsentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Cross-hospital DATA-SHARING consent (Phase 2). A registrar/clinician records the patient's
 * opt-in (or refusal) to share their deep record across SmartTriage hospitals, keyed on national
 * ID. This consent gates the cross-hospital deep-record read; the Phase-1 safety summary is
 * unaffected (always available).
 */
@RestController
@RequestMapping("/api/v1/data-sharing-consents")
@RequiredArgsConstructor
public class DataSharingConsentController {

    private final DataSharingConsentService dataSharingConsentService;

    @PostMapping("/national-id/{nationalId}")
    @PreAuthorize("@clinicalAuthz.canManageDataSharingConsent(authentication)")
    public ResponseEntity<ApiResponse<DataSharingConsentResponse>> record(
            @PathVariable String nationalId,
            @Valid @RequestBody RecordDataSharingConsentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Data-sharing consent recorded", dataSharingConsentService.recordConsent(nationalId, request)));
    }

    @PutMapping("/{id}/withdraw")
    @PreAuthorize("@clinicalAuthz.canManageDataSharingConsent(authentication)")
    public ResponseEntity<ApiResponse<DataSharingConsentResponse>> withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody WithdrawConsentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Data-sharing consent withdrawn", dataSharingConsentService.withdrawConsent(id, request)));
    }

    @GetMapping("/national-id/{nationalId}")
    @PreAuthorize("@clinicalAuthz.canManageDataSharingConsent(authentication)")
    public ResponseEntity<ApiResponse<List<DataSharingConsentResponse>>> history(
            @PathVariable String nationalId) {
        return ResponseEntity.ok(ApiResponse.success(
                dataSharingConsentService.getConsentsForNationalId(nationalId)));
    }
}
