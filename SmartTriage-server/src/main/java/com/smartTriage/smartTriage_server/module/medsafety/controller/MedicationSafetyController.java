package com.smartTriage.smartTriage_server.module.medsafety.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.medsafety.dto.*;
import com.smartTriage.smartTriage_server.module.medsafety.service.MedicationSafetyService;
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

import java.util.List;
import java.util.UUID;

/**
 * Medication Safety Controller — exposes endpoints for prescription validation,
 * safety check management, and formulary browsing.
 *
 * POST   /api/v1/med-safety/validate              — Validate prescription
 * PUT    /api/v1/med-safety/{checkId}/override     — Override safety check
 * GET    /api/v1/med-safety/visit/{visitId}        — Safety checks for visit
 * GET    /api/v1/med-safety/formulary/{hospitalId} — Browse formulary (paginated)
 * GET    /api/v1/med-safety/formulary/search       — Search formulary
 * POST   /api/v1/med-safety/formulary              — Add formulary entry (admin)
 * PUT    /api/v1/med-safety/formulary/{id}         — Update formulary entry
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/med-safety")
@RequiredArgsConstructor
public class MedicationSafetyController {

    private final MedicationSafetyService medicationSafetyService;

    // ====================================================================
    // PRESCRIPTION VALIDATION
    // ====================================================================

    @PostMapping("/validate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationSafetyCheckResponse>> validatePrescription(
            @Valid @RequestBody ValidatePrescriptionRequest request) {
        MedicationSafetyCheckResponse response = medicationSafetyService.validatePrescription(request);
        return ResponseEntity.ok(ApiResponse.success("Prescription validated", response));
    }

    // ====================================================================
    // OVERRIDE
    // ====================================================================

    @PutMapping("/{checkId}/override")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessMedicationSafetyCheck(authentication, #checkId)")
    public ResponseEntity<ApiResponse<MedicationSafetyCheckResponse>> overrideSafetyCheck(
            @PathVariable UUID checkId,
            @Valid @RequestBody OverrideSafetyCheckRequest request) {
        // The overriding clinician is resolved from the authenticated principal in the
        // service — never from the request — so the forensic record cannot be spoofed.
        MedicationSafetyCheckResponse response = medicationSafetyService.overrideSafetyCheck(
                checkId, request.getReason());
        return ResponseEntity.ok(ApiResponse.success("Safety check overridden", response));
    }

    // ====================================================================
    // VISIT SAFETY CHECKS
    // ====================================================================

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<MedicationSafetyCheckResponse>>> getChecksForVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<MedicationSafetyCheckResponse> response = medicationSafetyService.getChecksForVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ====================================================================
    // FORMULARY
    // ====================================================================

    @GetMapping("/formulary/{hospitalId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<DrugFormularyResponse>>> getFormulary(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<DrugFormularyResponse> response = medicationSafetyService.getFormulary(hospitalId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/formulary/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<List<DrugFormularyResponse>>> searchFormulary(
            @RequestParam String query) {
        List<DrugFormularyResponse> response = medicationSafetyService.searchFormulary(query);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/formulary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<DrugFormularyResponse>> addFormularyEntry(
            @Valid @RequestBody DrugFormularyRequest request) {
        DrugFormularyResponse response = medicationSafetyService.addFormularyEntry(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Formulary entry added", response));
    }

    @PutMapping("/formulary/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<DrugFormularyResponse>> updateFormularyEntry(
            @PathVariable UUID id,
            @Valid @RequestBody DrugFormularyRequest request) {
        DrugFormularyResponse response = medicationSafetyService.updateFormularyEntry(id, request);
        return ResponseEntity.ok(ApiResponse.success("Formulary entry updated", response));
    }
}
