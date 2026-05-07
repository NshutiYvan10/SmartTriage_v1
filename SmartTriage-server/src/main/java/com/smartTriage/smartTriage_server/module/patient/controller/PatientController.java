package com.smartTriage.smartTriage_server.module.patient.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.CreatePatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.UpdatePregnancyStatusRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.UpdateAllergiesRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.UpdateChronicConditionsRequest;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Patient registration and lookup endpoints.
 * Accessible by REGISTRAR, NURSE, DOCTOR roles. Triage/charge nurses
 * are NURSE-role users with a designation — see Designation enum.
 */
@RestController
@RequestMapping("/api/v1/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<PatientResponse>> createPatient(
            @Valid @RequestBody CreatePatientRequest request) {
        PatientResponse response = patientService.createPatient(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Patient registered successfully", response));
    }

    /**
     * Combined registration — creates Patient + Visit in one atomic transaction.
     * Prevents the issue where a patient record exists but no matching visit.
     */
    @PostMapping("/register")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<RegisterPatientResponse>> registerPatient(
            @Valid @RequestBody RegisterPatientRequest request) {
        RegisterPatientResponse response = patientService.registerPatientWithVisit(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Patient and visit created successfully", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PatientResponse>> getPatient(@PathVariable UUID id) {
        PatientResponse response = patientService.getPatientById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}")
    public ResponseEntity<ApiResponse<Page<PatientResponse>>> getPatientsByHospital(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PatientResponse> response = patientService.getPatientsByHospital(hospitalId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/search")
    public ResponseEntity<ApiResponse<Page<PatientResponse>>> searchPatients(
            @PathVariable UUID hospitalId,
            @RequestParam String query,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PatientResponse> response = patientService.searchPatients(hospitalId, query, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Update pregnancy status — clinical-safety lever for teratogen checks.
     * Not available to REGISTRAR (non-clinical role).
     */
    @PatchMapping("/{id}/pregnancy-status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<PatientResponse>> updatePregnancyStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePregnancyStatusRequest request) {
        PatientResponse response = patientService.updatePregnancyStatus(id, request.getPregnancyStatus());
        return ResponseEntity.ok(ApiResponse.success("Pregnancy status updated", response));
    }

    /**
     * Update the patient's free-text known allergies. Drives the medication
     * safety engine's cross-reactivity check on every prescribe — a stale
     * or missing allergy here is a real safety risk, which is why mid-visit
     * edit needs to be possible. REGISTRAR is excluded; updating allergies
     * is a clinical decision.
     */
    @PatchMapping("/{id}/allergies")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<PatientResponse>> updateAllergies(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAllergiesRequest request) {
        PatientResponse response = patientService.updateKnownAllergies(id, request.getKnownAllergies());
        return ResponseEntity.ok(ApiResponse.success("Allergies updated", response));
    }

    /** Update the patient's free-text chronic conditions. */
    @PatchMapping("/{id}/chronic-conditions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<PatientResponse>> updateChronicConditions(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateChronicConditionsRequest request) {
        PatientResponse response = patientService.updateChronicConditions(id, request.getChronicConditions());
        return ResponseEntity.ok(ApiResponse.success("Chronic conditions updated", response));
    }
}
