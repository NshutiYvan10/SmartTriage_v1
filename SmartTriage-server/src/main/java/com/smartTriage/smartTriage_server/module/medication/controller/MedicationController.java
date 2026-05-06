package com.smartTriage.smartTriage_server.module.medication.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.CountersignMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.PrescribeMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * Medication Administration Record (MAR) endpoints.
 *
 * Supports the full MAR workflow from the Rwanda national triage forms:
 *   POST   /api/v1/medications                           → Prescribe
 *   PATCH  /api/v1/medications/{id}/administer            → Record administration
 *   PATCH  /api/v1/medications/{id}/countersign           → Countersign
 *   PATCH  /api/v1/medications/{id}/hold                  → Hold
 *   PATCH  /api/v1/medications/{id}/cancel                → Cancel
 *   PATCH  /api/v1/medications/{id}/refuse                → Patient refused
 *   GET    /api/v1/medications/{id}                       → Single record
 *   GET    /api/v1/medications/visit/{visitId}            → Paginated list
 *   GET    /api/v1/medications/visit/{visitId}/all        → Full list (MAR sheet)
 */
@RestController
@RequestMapping("/api/v1/medications")
@RequiredArgsConstructor
public class MedicationController {

    private final MedicationService medicationService;

    // ====================================================================
    // PRESCRIBE
    // ====================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationResponse>> prescribe(
            @Valid @RequestBody PrescribeMedicationRequest request) {
        MedicationResponse response = medicationService.prescribe(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Medication prescribed", response));
    }

    // ====================================================================
    // ADMINISTER
    // ====================================================================

    @PatchMapping("/{id}/administer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationResponse>> administer(
            @PathVariable UUID id,
            @RequestBody AdministerMedicationRequest request) {
        MedicationResponse response = medicationService.administer(id, request);
        return ResponseEntity.ok(ApiResponse.success("Medication administered", response));
    }

    // ====================================================================
    // COUNTERSIGN
    // ====================================================================

    @PatchMapping("/{id}/countersign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationResponse>> countersign(
            @PathVariable UUID id,
            @RequestBody CountersignMedicationRequest request) {
        MedicationResponse response = medicationService.countersign(id, request);
        return ResponseEntity.ok(ApiResponse.success("Medication countersigned", response));
    }

    // ====================================================================
    // STATUS CHANGES
    // ====================================================================

    @PatchMapping("/{id}/hold")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationResponse>> hold(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        MedicationResponse response = medicationService.holdMedication(id, reason);
        return ResponseEntity.ok(ApiResponse.success("Medication held", response));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<MedicationResponse>> cancel(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        MedicationResponse response = medicationService.cancelMedication(id, reason);
        return ResponseEntity.ok(ApiResponse.success("Medication cancelled", response));
    }

    @PatchMapping("/{id}/refuse")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationResponse>> refuse(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        MedicationResponse response = medicationService.refuseMedication(id, reason);
        return ResponseEntity.ok(ApiResponse.success("Medication refused", response));
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MedicationResponse>> getMedication(@PathVariable UUID id) {
        MedicationResponse response = medicationService.getMedication(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}")
    public ResponseEntity<ApiResponse<Page<MedicationResponse>>> getMedicationsByVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<MedicationResponse> response = medicationService.getMedicationsByVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/all")
    public ResponseEntity<ApiResponse<List<MedicationResponse>>> getAllMedicationsForVisit(
            @PathVariable UUID visitId) {
        List<MedicationResponse> response = medicationService.getAllMedicationsForVisit(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Patient-level medication history — every active prescription this
     * patient has had across all their visits, newest first. Drives the
     * doctor's "Reorder" affordance in the prescribing UI.
     */
    @GetMapping("/patient/{patientId}/history")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<List<MedicationResponse>>> getPatientMedicationHistory(
            @PathVariable UUID patientId) {
        List<MedicationResponse> response = medicationService.getMedicationHistoryForPatient(patientId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
