package com.smartTriage.smartTriage_server.module.patient.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientChronicConditionResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RecordChronicConditionRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.ResolveChronicConditionRequest;
import com.smartTriage.smartTriage_server.module.patient.service.PatientChronicConditionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Structured chronic-condition endpoints (V61). Same RBAC shape as
 * {@code PatientAllergyController}: REGISTRAR is excluded because
 * recording a chronic condition is a clinical judgement.
 */
@RestController
@RequiredArgsConstructor
public class PatientChronicConditionController {

    private final PatientChronicConditionService conditionService;

    @GetMapping("/api/v1/patients/{patientId}/structured-conditions")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR', 'LAB_TECHNICIAN', 'HOSPITAL_ADMIN', 'SUPER_ADMIN') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #patientId)")
    public ResponseEntity<ApiResponse<List<PatientChronicConditionResponse>>> listForPatient(
            @PathVariable UUID patientId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Active chronic conditions",
                conditionService.listActiveForPatient(patientId)));
    }

    @GetMapping("/api/v1/patients/{patientId}/structured-conditions/history")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR', 'HOSPITAL_ADMIN', 'SUPER_ADMIN') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #patientId)")
    public ResponseEntity<ApiResponse<List<PatientChronicConditionResponse>>> historyForPatient(
            @PathVariable UUID patientId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Chronic condition history",
                conditionService.listHistoryForPatient(patientId)));
    }

    @PostMapping("/api/v1/patients/{patientId}/structured-conditions")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #patientId)")
    public ResponseEntity<ApiResponse<PatientChronicConditionResponse>> record(
            @PathVariable UUID patientId,
            @Valid @RequestBody RecordChronicConditionRequest request) {
        PatientChronicConditionResponse created = conditionService.record(patientId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Chronic condition recorded", created));
    }

    /**
     * Mark a chronic condition as RESOLVED. Restricted to DOCTOR —
     * removing a condition from the safety-engine feed is a clinical
     * decision that carries patient-safety risk.
     */
    @PostMapping("/api/v1/patient-chronic-conditions/{conditionId}/resolve")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<ApiResponse<PatientChronicConditionResponse>> resolve(
            @PathVariable UUID conditionId,
            @Valid @RequestBody ResolveChronicConditionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Chronic condition resolved",
                conditionService.resolve(conditionId, request)));
    }
}
