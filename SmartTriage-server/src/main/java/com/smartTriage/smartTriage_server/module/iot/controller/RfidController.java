package com.smartTriage.smartTriage_server.module.iot.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.OpenVisitForCardRequest;
import com.smartTriage.smartTriage_server.module.iot.dto.ReplaceCardRequest;
import com.smartTriage.smartTriage_server.module.iot.dto.RfidTapRequest;
import com.smartTriage.smartTriage_server.module.iot.dto.RfidTapResponse;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.service.DeviceService;
import com.smartTriage.smartTriage_server.module.iot.service.RfidService;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * RFID registration-reader API (V95).
 *
 * <ul>
 *   <li>{@code POST /tap} — the reader (device-API-key authed, NOT JWT) sends a card UID; the
 *       backend resolves identity system-wide and pushes the result to the registrar's dashboard.</li>
 *   <li>{@code POST /devices/{id}/bind-mode} — registrar arms tap-to-capture for a desk reader.</li>
 *   <li>{@code POST /open-visit} — registrar confirms an RFID-found patient and opens a fresh visit.</li>
 *   <li>{@code GET /devices/hospital/{id}} — RFID readers at a hospital (desk-device picker).</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/iot/rfid")
@RequiredArgsConstructor
public class RfidController {

    private final DeviceService deviceService;
    private final RfidService rfidService;

    /** Device endpoint — authenticated by the pre-shared {@code X-Device-API-Key}, like vital ingest. */
    @PostMapping("/tap")
    public ResponseEntity<RfidTapResponse> tap(
            @RequestHeader("X-Device-API-Key") String apiKey,
            @Valid @RequestBody RfidTapRequest request) {
        IoTDevice device;
        try {
            device = deviceService.authenticateDevice(apiKey);
        } catch (Exception e) {
            return ResponseEntity.status(401)
                    .body(RfidTapResponse.builder().result("NOT_FOUND").build());
        }
        return ResponseEntity.ok(rfidService.tap(device.getId(), request.getCardId()));
    }

    /** Arm the registration tap-to-capture window on a desk reader. */
    @PostMapping("/devices/{deviceId}/bind-mode")
    @PreAuthorize("@clinicalAuthz.canOperateRfidDevice(authentication, #deviceId)")
    public ResponseEntity<ApiResponse<Void>> armBindMode(@PathVariable UUID deviceId) {
        rfidService.armBindMode(deviceId);
        return ResponseEntity.ok(ApiResponse.success("Bind mode armed — tap the card now", null));
    }

    /** Registrar confirms an RFID-found patient and opens a fresh visit at this hospital. */
    @PostMapping("/open-visit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','REGISTRAR','NURSE','DOCTOR') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #request.hospitalId)")
    public ResponseEntity<ApiResponse<RegisterPatientResponse>> openVisit(
            @Valid @RequestBody OpenVisitForCardRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Visit opened",
                rfidService.openVisitForCard(request)));
    }

    /**
     * Replace a patient's RFID card (lost/damaged-card workflow). Sets the new card on the shared
     * identity so the old card stops resolving; rejects a card already held by another patient.
     * Gated to the registration-desk audience at the patient's own hospital; audited (old → new).
     */
    @PutMapping("/replace-card")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','REGISTRAR') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #request.patientId)")
    public ResponseEntity<ApiResponse<PatientResponse>> replaceCard(@Valid @RequestBody ReplaceCardRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Card replaced",
                rfidService.replaceCardForPatient(request.getPatientId(), request.getNewCardId())));
    }

    /** RFID readers registered at a hospital — for the registration desk-device picker. */
    @GetMapping("/devices/hospital/{hospitalId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','REGISTRAR') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> listDevices(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(rfidService.listDevices(hospitalId)));
    }
}
