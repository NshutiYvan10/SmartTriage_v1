package com.smartTriage.smartTriage_server.module.patient.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientAllergyResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RecordAllergyRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.RefuteAllergyRequest;
import com.smartTriage.smartTriage_server.module.patient.service.PatientAllergyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for the structured patient-allergy model (V58).
 *
 * <p>The legacy free-text {@code PATCH /api/v1/patients/{id}/allergies}
 * endpoint still works — un-migrated records that have not yet been
 * re-captured as structured rows continue to flow through it, and
 * {@code MedicationSafetyEngine} reads the legacy column as a
 * fallback. New entries should go through this controller.
 *
 * <p>RBAC: matches the legacy endpoint — REGISTRAR is excluded
 * because recording an allergy carries clinical decision-making
 * weight (severity grading, reaction documentation).
 */
@RestController
@RequiredArgsConstructor
public class PatientAllergyController {

    private final PatientAllergyService allergyService;

    // ====================================================================
    // LIST  /api/v1/patients/{patientId}/structured-allergies
    // ====================================================================

    @GetMapping("/api/v1/patients/{patientId}/structured-allergies")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR', 'LAB_TECHNICIAN', 'HOSPITAL_ADMIN', 'SUPER_ADMIN') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #patientId)")
    public ResponseEntity<ApiResponse<List<PatientAllergyResponse>>> listForPatient(
            @PathVariable UUID patientId) {
        List<PatientAllergyResponse> rows = allergyService.listActiveForPatient(patientId);
        return ResponseEntity.ok(ApiResponse.success("Active allergies", rows));
    }

    @GetMapping("/api/v1/patients/{patientId}/structured-allergies/history")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR', 'HOSPITAL_ADMIN', 'SUPER_ADMIN') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #patientId)")
    public ResponseEntity<ApiResponse<List<PatientAllergyResponse>>> historyForPatient(
            @PathVariable UUID patientId) {
        List<PatientAllergyResponse> rows = allergyService.listHistoryForPatient(patientId);
        return ResponseEntity.ok(ApiResponse.success("Allergy history", rows));
    }

    // ====================================================================
    // RECORD  POST /api/v1/patients/{patientId}/structured-allergies
    // ====================================================================

    @PostMapping("/api/v1/patients/{patientId}/structured-allergies")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #patientId)")
    public ResponseEntity<ApiResponse<PatientAllergyResponse>> recordAllergy(
            @PathVariable UUID patientId,
            @Valid @RequestBody RecordAllergyRequest request) {
        PatientAllergyResponse created = allergyService.record(patientId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Allergy recorded", created));
    }

    // ====================================================================
    // REFUTE  POST /api/v1/patient-allergies/{allergyId}/refute
    // ====================================================================

    /**
     * Mark a previously-recorded allergy as REFUTED. The row is not
     * hard-deleted — refute is itself an audit event. Restricted to
     * DOCTOR because clearing an allergy from the safety check is a
     * decision that carries patient-safety risk.
     */
    @PostMapping("/api/v1/patient-allergies/{allergyId}/refute")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<ApiResponse<PatientAllergyResponse>> refute(
            @PathVariable UUID allergyId,
            @Valid @RequestBody RefuteAllergyRequest request) {
        PatientAllergyResponse refuted = allergyService.refute(allergyId, request);
        return ResponseEntity.ok(ApiResponse.success("Allergy refuted", refuted));
    }
}
