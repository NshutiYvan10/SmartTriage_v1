package com.smartTriage.smartTriage_server.module.ems.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.ems.dto.*;
import com.smartTriage.smartTriage_server.module.ems.service.EmsRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * EMS / paramedic workflow endpoints.
 *
 * RBAC summary:
 *  - PARAMEDIC                       : create / update / preregister / add intervention / cancel own runs
 *  - NURSE / DOCTOR                  : confirm arrival, transfer of care (handover ack)
 *  - NURSE / DOCTOR / HOSPITAL_ADMIN : view inbound board
 *  - SUPER_ADMIN                     : everything
 *
 *  POST   /api/v1/ems/runs                       — paramedic starts run
 *  PATCH  /api/v1/ems/runs/{id}                  — update fields (vitals, mechanism, field triage…)
 *  POST   /api/v1/ems/runs/{id}/interventions    — add one intervention
 *  POST   /api/v1/ems/runs/{id}/preregister      — pre-arrival ping (creates Visit)
 *  POST   /api/v1/ems/runs/{id}/confirm-arrival  — patient at the door
 *  POST   /api/v1/ems/runs/{id}/transfer-of-care — receiving nurse acks handover
 *  POST   /api/v1/ems/runs/{id}/cancel           — cancel run
 *  GET    /api/v1/ems/runs/{id}                  — single run + interventions
 *  GET    /api/v1/ems/runs/mine                  — paramedic's history
 *  GET    /api/v1/ems/hospital/{id}/inbound      — charge-nurse board
 *  GET    /api/v1/ems/visits/{visitId}           — run for a given visit (visit detail page)
 */
@RestController
@RequestMapping("/api/v1/ems")
@RequiredArgsConstructor
public class EmsRunController {

    private final EmsRunService emsRunService;

    @PostMapping("/runs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> create(
            @Valid @RequestBody CreateEmsRunRequest request) {
        EmsRunResponse response = emsRunService.createRun(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("EMS run started", response));
    }

    @PatchMapping("/runs/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> update(
            @PathVariable UUID id, @RequestBody UpdateEmsRunRequest request) {
        return ResponseEntity.ok(ApiResponse.success(emsRunService.updateRun(id, request)));
    }

    @PostMapping("/runs/{id}/interventions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> addIntervention(
            @PathVariable UUID id, @Valid @RequestBody AddInterventionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Intervention recorded",
                emsRunService.addIntervention(id, request)));
    }

    /**
     * Compute the field triage with the shared in-hospital engine (adult or
     * KFH peds). Returns the run with the computed category, TEWS and
     * decision-path populated.
     */
    @PostMapping("/runs/{id}/field-triage")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> fieldTriage(
            @PathVariable UUID id, @Valid @RequestBody FieldTriageRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Field triage computed",
                emsRunService.computeFieldTriage(id, request)));
    }

    /** Toggle blue-light / priority transport. */
    @PostMapping("/runs/{id}/lights")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> lights(
            @PathVariable UUID id, @RequestParam(defaultValue = "true") boolean active) {
        return ResponseEntity.ok(ApiResponse.success(
                active ? "Lights activated" : "Lights cleared",
                emsRunService.setLights(id, active)));
    }

    /** Redirect an in-flight run to a different destination hospital. */
    @PostMapping("/runs/{id}/reroute")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> reroute(
            @PathVariable UUID id, @Valid @RequestBody RerouteRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Run rerouted",
                emsRunService.reroute(id, request)));
    }

    @PostMapping("/runs/{id}/preregister")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> preregister(
            @PathVariable UUID id,
            @RequestBody(required = false) PreregisterRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Pre-arrival sent to ED",
                emsRunService.preregister(id, request)));
    }

    @PostMapping("/runs/{id}/confirm-arrival")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> confirmArrival(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Arrival confirmed",
                emsRunService.confirmArrival(id)));
    }

    /**
     * Receiving nurse / doctor acknowledges RECEIPT of the patient at the door
     * (the "Received" lifecycle step) — a reliable single-click confirmation that
     * also clears the ambulance's Alert-Center notifications. Distinct from the
     * formal read-back handover below. ED-side only (a paramedic hands over; the
     * ED receives).
     */
    @PostMapping("/runs/{id}/acknowledge-arrival")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> acknowledgeArrival(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Arrival receipt acknowledged",
                emsRunService.acknowledgeArrival(id)));
    }

    /**
     * Receiving nurse / doctor acknowledges the handover. Paramedics
     * are NOT permitted (the receiving clinician must be a different
     * actor) — service layer enforces this defensively.
     */
    @PostMapping("/runs/{id}/transfer-of-care")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> transferOfCare(
            @PathVariable UUID id,
            @RequestBody(required = false) TransferOfCareRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Handover acknowledged",
                emsRunService.transferOfCare(id, request)));
    }

    @PostMapping("/runs/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> cancel(
            @PathVariable UUID id, @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.success("Run cancelled",
                emsRunService.cancelRun(id, reason)));
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC', 'NURSE', 'DOCTOR', 'HOSPITAL_ADMIN', 'READ_ONLY')")
    public ResponseEntity<ApiResponse<EmsRunResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(emsRunService.getById(id)));
    }

    /**
     * Download the run's Patient Care Report (PCR) as a PDF — the standard pre-hospital handoff
     * artifact. Same audience + per-run scope as {@link #getById} (a paramedic gets only their own
     * runs; ED staff/admin only runs at their hospital; super-admin any — enforced in the service).
     */
    @GetMapping("/runs/{id}/pcr")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC', 'NURSE', 'DOCTOR', 'HOSPITAL_ADMIN', 'READ_ONLY')")
    public ResponseEntity<byte[]> downloadPcr(@PathVariable UUID id) {
        var pdf = emsRunService.renderPcr(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.filename() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.bytes());
    }

    @GetMapping("/runs/mine")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC')")
    public ResponseEntity<ApiResponse<List<EmsRunResponse>>> mine() {
        return ResponseEntity.ok(ApiResponse.success(emsRunService.getMyRuns()));
    }

    @GetMapping("/hospital/{hospitalId}/inbound")
    // The inbound board is the RECEIVING hospital's view of incoming ambulances —
    // ED staff (+ admin) only. Paramedics see their own runs via /runs/mine; they
    // must NOT read every crew's hospital-wide inbound runs.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'NURSE', 'DOCTOR', 'HOSPITAL_ADMIN') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<EmsRunResponse>>> inbound(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(emsRunService.getInbound(hospitalId)));
    }

    @GetMapping("/visits/{visitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC', 'NURSE', 'DOCTOR', 'HOSPITAL_ADMIN', 'READ_ONLY') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<EmsRunResponse>> getByVisit(@PathVariable UUID visitId) {
        return emsRunService.getByVisitId(visitId)
                .map(r -> ResponseEntity.ok(ApiResponse.success(r)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.success(null)));
    }

    /** Safety-critical patient context (allergies, prior visits) for a linked run. */
    @GetMapping("/runs/{id}/patient-history")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<PatientHistoryResponse>> patientHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(emsRunService.getPatientHistory(id)));
    }

    /** Active hospitals the paramedic can pick as a destination. */
    @GetMapping("/destinations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARAMEDIC', 'NURSE', 'DOCTOR')")
    public ResponseEntity<ApiResponse<List<DestinationHospitalResponse>>> destinations() {
        return ResponseEntity.ok(ApiResponse.success(emsRunService.listDestinations()));
    }
}
