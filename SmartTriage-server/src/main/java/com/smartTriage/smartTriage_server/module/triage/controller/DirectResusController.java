package com.smartTriage.smartTriage_server.module.triage.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.ResolveIdentityRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.mapper.PatientMapper;
import com.smartTriage.smartTriage_server.module.patient.service.PatientIdentityService;
import com.smartTriage.smartTriage_server.module.triage.dto.DirectResusAdmissionRequest;
import com.smartTriage.smartTriage_server.module.triage.dto.DirectResusAdmissionResponse;
import com.smartTriage.smartTriage_server.module.triage.service.DirectResusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoints for the Direct Resus Admission pathway (V28).
 *
 * <p>Endpoint groups:
 * <ul>
 *   <li><b>POST /api/v1/admissions/direct-resus</b> — admit a Red patient
 *       straight to RESUS, bypassing the standard triage form. Creates
 *       the patient (or placeholder), visit, auto-RED triage record, and
 *       attempts bed placement. Always succeeds (overflow if no bed).</li>
 *   <li><b>POST /api/v1/admissions/{visitId}/confirm-arrival</b> — for
 *       ambulance pre-arrivals: marks the patient as physically arrived
 *       and starts the door clock.</li>
 *   <li><b>POST /api/v1/patients/{id}/resolve-identity</b> — replace
 *       a placeholder Patient ("Unknown Alpha") with the real identity,
 *       or merge into an existing MPI record.</li>
 * </ul>
 *
 * <p>Permissions: Direct Resus Admission requires a clinical role —
 * TRIAGE_NURSE, NURSE, DOCTOR, HOSPITAL_ADMIN, or SUPER_ADMIN. Identity
 * resolution accepts the same set (the receiving doctor often resolves
 * identity at handover).
 */
@RestController
@RequiredArgsConstructor
public class DirectResusController {

    private final DirectResusService directResusService;
    private final PatientIdentityService patientIdentityService;

    // ════════════════════════════════════════════════════════════════
    // Direct Resus Admission
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/api/v1/admissions/direct-resus")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<DirectResusAdmissionResponse>> admit(
            @Valid @RequestBody DirectResusAdmissionRequest request) {
        DirectResusAdmissionResponse response = directResusService.admit(request);
        String message = response.isOverflow()
                ? "Patient admitted (RESUS overflow — transfer prompt active)"
                : "Patient admitted to RESUS";
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(message, response));
    }

    @PostMapping("/api/v1/admissions/{visitId}/confirm-arrival")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<DirectResusAdmissionResponse>> confirmArrival(
            @PathVariable UUID visitId) {
        DirectResusAdmissionResponse response = directResusService.confirmArrival(visitId);
        return ResponseEntity.ok(ApiResponse.success("Arrival confirmed — door clock started", response));
    }

    // ════════════════════════════════════════════════════════════════
    // Identity Resolution
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/api/v1/patients/{patientId}/resolve-identity")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<PatientResponse>> resolveIdentity(
            @PathVariable UUID patientId,
            @Valid @RequestBody ResolveIdentityRequest request) {
        Patient resolved = patientIdentityService.resolveIdentity(patientId, request);
        String msg = request.getMergeIntoPatientId() != null
                ? "Identity resolved — merged into existing patient record"
                : "Identity resolved";
        return ResponseEntity.ok(ApiResponse.success(msg, PatientMapper.toResponse(resolved)));
    }
}
