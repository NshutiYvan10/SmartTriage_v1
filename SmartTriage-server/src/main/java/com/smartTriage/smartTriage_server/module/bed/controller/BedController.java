package com.smartTriage.smartTriage_server.module.bed.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.bed.dto.AssignDeviceRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.BedResponse;
import com.smartTriage.smartTriage_server.module.bed.dto.CreateBedRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.PlacePatientRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.TransferPatientRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.UpdateBedRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.ZoneOccupancyResponse;
import com.smartTriage.smartTriage_server.module.bed.service.BedService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * BedController — REST endpoints for bed management and patient placement.
 *
 * The bed layer is the clinical routing surface between triage and
 * monitoring: a triaged patient gets placed in a bed, and the bed's
 * assigned IoT monitor (if any) automatically begins streaming vitals to
 * that patient's chart — no manual pair click.
 *
 * Endpoint groups:
 *   Admin CRUD     — POST/PATCH/DELETE /api/v1/beds             (HOSPITAL_ADMIN)
 *   Queries        — GET  /api/v1/beds/...                      (all staff)
 *   Placement      — POST /api/v1/beds/{id}/place               (clinical staff)
 *   Transfer       — POST /api/v1/beds/{id}/transfer
 *   Discharge      — POST /api/v1/beds/{id}/discharge
 *   Housekeeping   — POST /api/v1/beds/{id}/mark-cleaned
 *                    POST /api/v1/beds/{id}/mark-out-of-service
 *                    POST /api/v1/beds/{id}/mark-available
 *   Device mgmt    — POST /api/v1/beds/{id}/assign-device       (HOSPITAL_ADMIN)
 */
@RestController
@RequestMapping("/api/v1/beds")
@RequiredArgsConstructor
public class BedController {

    private final BedService bedService;

    // ====================================================================
    // ADMIN CRUD
    // ====================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<BedResponse>> createBed(
            @Valid @RequestBody CreateBedRequest request) {
        BedResponse response = bedService.createBed(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Bed created", response));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<BedResponse>> updateBed(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateBedRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Bed updated", bedService.updateBed(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteBed(@PathVariable UUID id) {
        bedService.deleteBed(id);
        return ResponseEntity.ok(ApiResponse.success("Bed deleted", null));
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<BedResponse>> getBed(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(bedService.getBed(id)));
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<BedResponse>>> getBedsForHospital(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(bedService.getBedsForHospital(hospitalId)));
    }

    @GetMapping("/hospital/{hospitalId}/zone/{zone}")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<BedResponse>>> getBedsByZone(
            @PathVariable UUID hospitalId,
            @PathVariable EdZone zone) {
        return ResponseEntity.ok(ApiResponse.success(bedService.getBedsByZone(hospitalId, zone)));
    }

    /**
     * Zone occupancy snapshot — every bed plus headline metrics for the
     * bed-grid header ("6 of 8 occupied"). One call per zone view.
     */
    @GetMapping("/hospital/{hospitalId}/zone/{zone}/occupancy")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<ZoneOccupancyResponse>> getZoneOccupancy(
            @PathVariable UUID hospitalId,
            @PathVariable EdZone zone) {
        return ResponseEntity.ok(ApiResponse.success(bedService.getZoneOccupancy(hospitalId, zone)));
    }

    @GetMapping("/hospital/{hospitalId}/zone/{zone}/available")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<BedResponse>>> getAvailableInZone(
            @PathVariable UUID hospitalId,
            @PathVariable EdZone zone) {
        return ResponseEntity.ok(ApiResponse.success(bedService.getAvailableInZone(hospitalId, zone)));
    }

    // ====================================================================
    // PLACEMENT WORKFLOW
    // ====================================================================

    @PostMapping("/{id}/place")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<BedResponse>> placePatient(
            @PathVariable UUID id,
            @Valid @RequestBody PlacePatientRequest request) {
        BedResponse response = bedService.placePatient(id, request, resolveActorName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Patient placed in bed", response));
    }

    @PostMapping("/{id}/transfer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<BedResponse>> transferPatient(
            @PathVariable UUID id,
            @Valid @RequestBody TransferPatientRequest request) {
        BedResponse response = bedService.transferPatient(id, request, resolveActorName());
        return ResponseEntity.ok(ApiResponse.success("Patient transferred", response));
    }

    @PostMapping("/{id}/discharge")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<BedResponse>> dischargePatient(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        BedResponse response = bedService.dischargePatient(id, reason);
        return ResponseEntity.ok(ApiResponse.success("Patient discharged from bed", response));
    }

    // ====================================================================
    // HOUSEKEEPING TRANSITIONS
    // ====================================================================

    @PostMapping("/{id}/mark-cleaned")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<BedResponse>> markCleaned(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Bed marked clean", bedService.markCleaned(id)));
    }

    @PostMapping("/{id}/mark-out-of-service")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<BedResponse>> markOutOfService(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.success("Bed out of service",
                bedService.markOutOfService(id, reason)));
    }

    @PostMapping("/{id}/mark-available")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<BedResponse>> markAvailable(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Bed available", bedService.markAvailable(id)));
    }

    // ====================================================================
    // DEVICE ASSIGNMENT
    // ====================================================================

    @PostMapping("/{id}/assign-device")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<BedResponse>> assignDevice(
            @PathVariable UUID id,
            @Valid @RequestBody AssignDeviceRequest request) {
        BedResponse response = bedService.assignDevice(id, request, resolveActorName());
        String msg = request.getDeviceId() != null ? "Device assigned to bed" : "Device detached from bed";
        return ResponseEntity.ok(ApiResponse.success(msg, response));
    }

    // ====================================================================
    // SEED DEFAULTS (Phase G #4)
    // ====================================================================

    /**
     * Backfill the default bed inventory for a hospital. Idempotent
     * per-zone: zones that already have any beds are skipped. Used as
     * recovery when a hospital pre-dates the auto-seed-on-create hook
     * (created post-V18 but before Phase G shipped) or when the tier
     * was corrected after creation.
     *
     * <p>Returns the seed result so the admin UI can show
     * "Seeded N beds across M zones, skipped K zones already populated".
     */
    @PostMapping("/hospital/{hospitalId}/seed-defaults")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<BedService.SeedResult>> seedDefaults(
            @PathVariable UUID hospitalId) {
        BedService.SeedResult result = bedService.seedDefaultBedsForHospital(hospitalId);
        String msg = result.bedsCreated() == 0
                ? "No new beds seeded (all zones already populated)"
                : "Seeded " + result.bedsCreated() + " beds across "
                        + result.zonesSeeded().size() + " zone(s)";
        return ResponseEntity.ok(ApiResponse.success(msg, result));
    }

    // ====================================================================
    // INTERNAL
    // ====================================================================

    /** Resolve the authenticated user's display name for audit logs. */
    private String resolveActorName() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) {
                String full = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                        + (user.getLastName() != null ? user.getLastName() : "")).trim();
                return full.isEmpty() ? user.getUsername() : full;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "System";
    }
}
