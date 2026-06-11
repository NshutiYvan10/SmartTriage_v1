package com.smartTriage.smartTriage_server.module.shift.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftTemplateResponse;
import com.smartTriage.smartTriage_server.module.shift.dto.UpsertShiftTemplateRequest;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Shift Template Controller — Hospital Admins (and SUPER_ADMIN) manage the
 * reusable per-shift rosters that the scheduler materializes at each shift
 * boundary.
 *
 * Authority is enforced via {@code shiftAssignmentAuthz.canManageTemplates}
 * so SUPER_ADMIN can manage any hospital and HOSPITAL_ADMIN can only manage
 * their own.
 */
@RestController
@RequestMapping("/api/v1/shift-templates")
@RequiredArgsConstructor
public class ShiftTemplateController {

    private final ShiftTemplateService shiftTemplateService;

    /** List all active templates for a hospital (typically DAY + NIGHT). */
    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("@shiftAssignmentAuthz.canViewShift(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<ShiftTemplateResponse>>> listForHospital(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(shiftTemplateService.listForHospital(hospitalId)));
    }

    /** Fetch a single template with its full assignment roster. */
    @GetMapping("/{templateId}")
    public ResponseEntity<ApiResponse<ShiftTemplateResponse>> getById(@PathVariable UUID templateId) {
        return ResponseEntity.ok(ApiResponse.success(shiftTemplateService.getById(templateId)));
    }

    /**
     * Create a new template. If one already exists for the same
     * (hospital, shiftPeriod), it is soft-deleted first so the new one
     * becomes the single active layout.
     */
    @PostMapping("/hospital/{hospitalId}")
    @PreAuthorize("@shiftAssignmentAuthz.canManageTemplates(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<ShiftTemplateResponse>> create(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody UpsertShiftTemplateRequest request) {
        ShiftTemplateResponse created = shiftTemplateService.create(hospitalId, request);
        return ResponseEntity.ok(ApiResponse.success("Shift template created", created));
    }

    /** Replace an existing template's contents (name, period, roster). */
    @PutMapping("/{templateId}")
    // Authz sweep — the body carries no hospitalId (it comes from the
    // stored template), so hospital-level canManageTemplates cannot be
    // expressed here. Role-gate matches DELETE below; proper per-template
    // scoping needs a template-aware authz method (flagged in the sweep
    // report as follow-up).
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'NURSE')")
    public ResponseEntity<ApiResponse<ShiftTemplateResponse>> update(
            @PathVariable UUID templateId,
            @Valid @RequestBody UpsertShiftTemplateRequest request) {
        ShiftTemplateResponse updated = shiftTemplateService.update(templateId, request);
        return ResponseEntity.ok(ApiResponse.success("Shift template updated", updated));
    }

    /** Soft-delete a template (so history stays queryable). */
    @DeleteMapping("/{templateId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'NURSE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID templateId) {
        shiftTemplateService.delete(templateId);
        return ResponseEntity.ok(ApiResponse.success("Shift template deleted", null));
    }
}
