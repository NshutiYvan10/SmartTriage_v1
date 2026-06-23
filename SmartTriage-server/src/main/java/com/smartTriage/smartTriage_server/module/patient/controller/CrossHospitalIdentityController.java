package com.smartTriage.smartTriage_server.module.patient.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalDeepRecordResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalSafetySummaryResponse;
import com.smartTriage.smartTriage_server.module.patient.service.CrossHospitalDeepRecordService;
import com.smartTriage.smartTriage_server.module.patient.service.CrossHospitalIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-hospital patient identity (Phase 1). Lets a registrar / clinician at any SmartTriage
 * hospital look a person up by national ID and see the shared minimal SAFETY SUMMARY — so a
 * returning patient isn't re-registered blank and the life-critical data is visible. Deep
 * clinical records remain hospital-owned (later phase).
 */
@RestController
@RequestMapping("/api/v1/patient-identity")
@RequiredArgsConstructor
public class CrossHospitalIdentityController {

    private final CrossHospitalIdentityService crossHospitalIdentityService;
    private final CrossHospitalDeepRecordService crossHospitalDeepRecordService;

    /**
     * Cross-hospital safety summary for a national ID. The read deliberately spans hospitals
     * (safety floor) — gated by role only, NOT by hospital membership — and is audited server-side.
     */
    @GetMapping("/safety-summary")
    @PreAuthorize("@clinicalAuthz.canReadCrossHospitalSafetySummary(authentication)")
    public ResponseEntity<ApiResponse<CrossHospitalSafetySummaryResponse>> getSafetySummary(
            @RequestParam String nationalId) {
        return ResponseEntity.ok(ApiResponse.success(
                crossHospitalIdentityService.getByNationalId(nationalId)));
    }

    /**
     * Cross-hospital DEEP clinical-history summary (Phase 2). Bounded + provenance-tagged, served
     * ONLY when the data gate allows it: the patient's effective data-sharing CONSENT, or — in an
     * emergency — a non-blank {@code breakTheGlassReason} (recorded as an immutable forensic event).
     * Absent both → DENIED with no clinical data. Role-gated (NOT hospital-scoped) and audited.
     */
    @GetMapping("/deep-record")
    @PreAuthorize("@clinicalAuthz.canAccessCrossHospitalDeepRecord(authentication)")
    public ResponseEntity<ApiResponse<CrossHospitalDeepRecordResponse>> getDeepRecord(
            @RequestParam String nationalId,
            @RequestParam(required = false) String breakTheGlassReason) {
        return ResponseEntity.ok(ApiResponse.success(
                crossHospitalDeepRecordService.getByNationalId(nationalId, breakTheGlassReason)));
    }
}
