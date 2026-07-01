package com.smartTriage.smartTriage_server.module.medication.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.ApproveOrderRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.CountersignMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.DelayDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.DiscontinueOrderRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.InfusionEventRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationDoseResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationOrderAuditResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.ModifyOrderRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.PrescribeMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.RecordPrnDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.RefuseDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.ZoneMedicationBoardResponse;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationScheduleService;
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
    private final MedicationScheduleService medicationScheduleService;

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
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<MedicationResponse>> getMedication(@PathVariable UUID id) {
        MedicationResponse response = medicationService.getMedication(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<MedicationResponse>>> getMedicationsByVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<MedicationResponse> response = medicationService.getMedicationsByVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/all")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<MedicationResponse>>> getAllMedicationsForVisit(
            @PathVariable UUID visitId) {
        List<MedicationResponse> response = medicationService.getAllMedicationsForVisit(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Patient-level medication history — every active prescription this
     * patient has had across all their visits, newest first. Drives the
     * doctor's "Reorder" affordance in the prescribing UI. Cross-hospital
     * read is blocked by canAccessPatient — without it any DOCTOR/NURSE
     * could fetch any other hospital's medication trail by guessing IDs.
     */
    @GetMapping("/patient/{patientId}/history")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #patientId)")
    public ResponseEntity<ApiResponse<List<MedicationResponse>>> getPatientMedicationHistory(
            @PathVariable UUID patientId) {
        List<MedicationResponse> response = medicationService.getMedicationHistoryForPatient(patientId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Nurse medication queue (Workflow 3) — every PRESCRIBED
     * medication across the hospital that has not yet been
     * administered, sorted STAT → URGENT → ROUTINE then oldest
     * first. Drives the standalone "Medication Queue" page.
     */
    @GetMapping("/queue/{hospitalId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<MedicationResponse>>> getPendingQueueForHospital(
            @PathVariable UUID hospitalId) {
        List<MedicationResponse> response = medicationService.getPendingQueueForHospital(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ====================================================================
    // MEDICATION MANAGEMENT (V67) — dose-level workflow
    //
    //   POST /{id}/approve            → charge-nurse approval (high-alert gate)
    //   POST /{id}/resume             → un-hold a HELD order
    //   POST /{id}/discontinue        → doctor stops the order (reason required)
    //   POST /{id}/modify             → discontinue-and-replace (modification chain)
    //   POST /doses/{doseId}/administer  → give a DUE dose (verify / witness / gates)
    //   POST /doses/{doseId}/delay       → push a DUE dose forward (reason required)
    //   POST /doses/{doseId}/refuse      → patient refused this dose
    //   POST /{id}/prn-dose           → record a PRN administration (gated)
    //   POST /{id}/infusion/start|rate|stop → continuous-infusion events
    //   GET  /board/{hospitalId}?zone=   → zone medication board
    //   GET  /visit/{visitId}/audit      → structured per-visit audit trail
    // ====================================================================

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationResponse>> approveOrder(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ApproveOrderRequest request) {
        MedicationResponse response = medicationScheduleService.approveOrder(
                id, request != null ? request : new ApproveOrderRequest());
        return ResponseEntity.ok(ApiResponse.success("Order approved", response));
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationResponse>> resumeOrder(@PathVariable UUID id) {
        MedicationResponse response = medicationScheduleService.resumeOrder(id);
        return ResponseEntity.ok(ApiResponse.success("Order resumed", response));
    }

    @PostMapping("/{id}/discontinue")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<MedicationResponse>> discontinueOrder(
            @PathVariable UUID id,
            @Valid @RequestBody DiscontinueOrderRequest request) {
        MedicationResponse response = medicationScheduleService.discontinueOrder(id, request);
        return ResponseEntity.ok(ApiResponse.success("Order discontinued", response));
    }

    @PostMapping("/{id}/modify")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<MedicationResponse>> modifyOrder(
            @PathVariable UUID id,
            @Valid @RequestBody ModifyOrderRequest request) {
        MedicationResponse response = medicationService.modifyOrder(id, request);
        return ResponseEntity.ok(ApiResponse.success("Order modified", response));
    }

    @PostMapping("/doses/{doseId}/administer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationDoseResponse>> administerDose(
            @PathVariable UUID doseId,
            @Valid @RequestBody(required = false) AdministerDoseRequest request) {
        MedicationDoseResponse response = medicationScheduleService.administerDose(
                doseId, request != null ? request : new AdministerDoseRequest());
        return ResponseEntity.ok(ApiResponse.success("Dose administered", response));
    }

    @PostMapping("/doses/{doseId}/delay")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationDoseResponse>> delayDose(
            @PathVariable UUID doseId,
            @Valid @RequestBody DelayDoseRequest request) {
        MedicationDoseResponse response = medicationScheduleService.delayDose(doseId, request);
        return ResponseEntity.ok(ApiResponse.success("Dose delayed", response));
    }

    @PostMapping("/doses/{doseId}/refuse")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationDoseResponse>> refuseDose(
            @PathVariable UUID doseId,
            @Valid @RequestBody RefuseDoseRequest request) {
        MedicationDoseResponse response = medicationScheduleService.refuseDose(doseId, request);
        return ResponseEntity.ok(ApiResponse.success("Dose refused", response));
    }

    @PostMapping("/{id}/prn-dose")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationDoseResponse>> recordPrnDose(
            @PathVariable UUID id,
            @Valid @RequestBody RecordPrnDoseRequest request) {
        MedicationDoseResponse response = medicationScheduleService.recordPrnDose(id, request);
        return ResponseEntity.ok(ApiResponse.success("PRN dose recorded", response));
    }

    @PostMapping("/{id}/infusion/start")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationDoseResponse>> startInfusion(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) InfusionEventRequest request) {
        MedicationDoseResponse response = medicationScheduleService.startInfusion(
                id, request != null ? request : new InfusionEventRequest());
        return ResponseEntity.ok(ApiResponse.success("Infusion started", response));
    }

    @PostMapping("/{id}/infusion/rate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationDoseResponse>> changeInfusionRate(
            @PathVariable UUID id,
            @Valid @RequestBody InfusionEventRequest request) {
        MedicationDoseResponse response = medicationScheduleService.changeInfusionRate(id, request);
        return ResponseEntity.ok(ApiResponse.success("Infusion rate changed", response));
    }

    @PostMapping("/{id}/infusion/stop")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<MedicationDoseResponse>> stopInfusion(
            @PathVariable UUID id,
            @Valid @RequestBody InfusionEventRequest request) {
        MedicationDoseResponse response = medicationScheduleService.stopInfusion(id, request);
        return ResponseEntity.ok(ApiResponse.success("Infusion stopped", response));
    }

    /**
     * Zone medication board — due / overdue doses, recent
     * administrations, live PRN orders, active infusions, and
     * pending-approval orders, optionally filtered to one zone.
     * Zone targeting is live: a patient transferred mid-prescription
     * appears on their NEW zone's board immediately.
     */
    @GetMapping("/board/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId) and "
            + "((#zone != null and @clinicalAuthz.canReceiveZoneAlerts(authentication, #hospitalId, #zone)) "
            + "or (#zone == null and @clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)))")
    public ResponseEntity<ApiResponse<ZoneMedicationBoardResponse>> getZoneBoard(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) EdZone zone) {
        ZoneMedicationBoardResponse response =
                medicationScheduleService.getZoneBoard(hospitalId, zone);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Structured per-visit medication audit trail: every order with
     * its complete dose timeline (given / missed / refused / delayed,
     * by whom, witnesses, overrides, reasons). The handover report
     * carries the same content as text.
     */
    @GetMapping("/visit/{visitId}/audit")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<MedicationOrderAuditResponse>>> getVisitAudit(
            @PathVariable UUID visitId) {
        List<MedicationOrderAuditResponse> response =
                medicationScheduleService.getVisitAudit(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
