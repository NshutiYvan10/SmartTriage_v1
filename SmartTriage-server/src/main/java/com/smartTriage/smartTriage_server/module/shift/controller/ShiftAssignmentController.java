package com.smartTriage.smartTriage_server.module.shift.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.shift.dto.ApplyTemplateRequest;
import com.smartTriage.smartTriage_server.module.shift.dto.BulkPlanResult;
import com.smartTriage.smartTriage_server.module.shift.dto.CopyWeekRequest;
import com.smartTriage.smartTriage_server.module.shift.dto.CreateShiftAssignmentRequest;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftAssignmentResponse;
import com.smartTriage.smartTriage_server.module.shift.repository.StaffLeaveRepository;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftPlanningService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shift Assignment Controller — charge nurse assigns staff to ED zones per
 * shift.
 * Controls who receives zone-routed alerts.
 */
@RestController
@RequestMapping("/api/v1/shifts")
@RequiredArgsConstructor
public class ShiftAssignmentController {

    private final ShiftAssignmentService shiftAssignmentService;
    private final ShiftPlanningService shiftPlanningService;
    private final StaffLeaveRepository staffLeaveRepository;

    /**
     * Assign a staff member to a zone for the current shift.
     */
    @PostMapping("/hospital/{hospitalId}/assign")
    @PreAuthorize("@shiftAssignmentAuthz.canAssign(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<ShiftAssignmentResponse>> assignToZone(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody CreateShiftAssignmentRequest request) {
        ShiftAssignmentResponse response = shiftAssignmentService.assignToZone(hospitalId, request);
        return ResponseEntity.ok(ApiResponse.success("Staff assigned to zone", response));
    }

    /**
     * Get all assignments for the current shift.
     */
    @GetMapping("/hospital/{hospitalId}/current")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentResponse>>> getCurrentShift(
            @PathVariable UUID hospitalId) {
        List<ShiftAssignmentResponse> assignments = shiftAssignmentService.getCurrentShiftAssignments(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(assignments));
    }

    /**
     * Get assignments for a specific zone.
     */
    @GetMapping("/hospital/{hospitalId}/zone/{zone}")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentResponse>>> getZoneAssignments(
            @PathVariable UUID hospitalId,
            @PathVariable EdZone zone) {
        List<ShiftAssignmentResponse> assignments = shiftAssignmentService.getZoneAssignments(hospitalId, zone);
        return ResponseEntity.ok(ApiResponse.success(assignments));
    }

    /**
     * Get current shift metadata (date, period).
     */
    @GetMapping("/current-period")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCurrentShiftPeriod() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "shiftDate", ShiftAssignmentService.getCurrentShiftDate().toString(),
                "shiftPeriod", ShiftAssignmentService.getCurrentShiftPeriod().name())));
    }

    /**
     * Phase 1 — return the authenticated user's currently-active shift
     * assignment, or an empty payload when they have none. Drives the
     * frontend's zone-scoped patient list:
     * <ul>
     *   <li>{@code zone} non-null → user sees only that zone's patients</li>
     *   <li>{@code shiftLead = true} → user sees all zones (charge
     *       nurse / shift lead has cross-zone visibility)</li>
     *   <li>empty payload → user has no active shift; frontend falls
     *       back to "patients I'm primary clinician on" only</li>
     * </ul>
     *
     * <p>The endpoint resolves the user from the security context, so
     * no path / query param is needed. Returns
     * {@code {assignment: null}} when there's no active assignment
     * for today's shift period rather than 404 — empty is a valid
     * state (off-shift) and the frontend wants to render gracefully.
     */
    @GetMapping("/me/current")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyCurrentShift() {
        User user = currentUser();
        Map<String, Object> body = new HashMap<>();
        if (user == null) {
            body.put("assignment", "");
            body.put("isOnApprovedLeave", false);
            return ResponseEntity.ok(ApiResponse.success(body));
        }
        Optional<ShiftAssignmentResponse> assignment =
                shiftAssignmentService.getCurrentShiftForUser(user.getId());
        body.put("assignment", assignment.<Object>map(a -> a).orElse(""));

        // V44+ off-duty indicator: tell the frontend whether the
        // authenticated user has an APPROVED leave row covering today.
        // Drives the "On Leave" badge in the sidebar header so a CN
        // who's officially off the floor sees an unmistakable cue
        // (and so colleagues looking at their profile know not to
        // route work to them). Approval gates on the backend already
        // block any accidental shift-management actions; this is the
        // matching UX cue.
        LocalDate todayKigali = LocalDate.now(ZoneId.of("Africa/Kigali"));
        boolean onLeave = !staffLeaveRepository
                .findApprovedCovering(user.getId(), todayKigali).isEmpty();
        body.put("isOnApprovedLeave", onLeave);

        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /** Resolves the User from the JWT-authenticated principal. */
    private User currentUser() {
        try {
            Object principal = SecurityContextHolder.getContext()
                    .getAuthentication().getPrincipal();
            return principal instanceof User u ? u : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Remove a shift assignment.
     */
    @DeleteMapping("/{assignmentId}")
    @PreAuthorize("@shiftAssignmentAuthz.canAssignForAssignment(authentication, #assignmentId)")
    public ResponseEntity<ApiResponse<Void>> removeAssignment(@PathVariable UUID assignmentId) {
        shiftAssignmentService.removeAssignment(assignmentId);
        return ResponseEntity.ok(ApiResponse.success("Assignment removed", null));
    }

    /**
     * Get assignments for a specific date (all shift periods).
     */
    @GetMapping("/hospital/{hospitalId}/date/{date}")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentResponse>>> getShiftByDate(
            @PathVariable UUID hospitalId,
            @PathVariable LocalDate date) {
        List<ShiftAssignmentResponse> assignments = shiftAssignmentService.getShiftByDate(hospitalId, date);
        return ResponseEntity.ok(ApiResponse.success(assignments));
    }

    /**
     * Get active shift history for a specific user.
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentResponse>>> getUserShiftHistory(
            @PathVariable UUID userId) {
        List<ShiftAssignmentResponse> history = shiftAssignmentService.getUserShiftHistory(userId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    /**
     * Update an existing shift assignment (change zone or function).
     */
    @PutMapping("/{assignmentId}")
    @PreAuthorize("@shiftAssignmentAuthz.canAssignForAssignment(authentication, #assignmentId)")
    public ResponseEntity<ApiResponse<ShiftAssignmentResponse>> updateAssignment(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody CreateShiftAssignmentRequest request) {
        ShiftAssignmentResponse response = shiftAssignmentService.updateAssignment(assignmentId, request);
        return ResponseEntity.ok(ApiResponse.success("Assignment updated", response));
    }

    /**
     * End a shift assignment (mark finished with timestamp).
     */
    @PatchMapping("/{assignmentId}/end")
    @PreAuthorize("@shiftAssignmentAuthz.canAssignForAssignment(authentication, #assignmentId)")
    public ResponseEntity<ApiResponse<ShiftAssignmentResponse>> endShift(@PathVariable UUID assignmentId) {
        ShiftAssignmentResponse response = shiftAssignmentService.endShift(assignmentId);
        return ResponseEntity.ok(ApiResponse.success("Shift ended", response));
    }

    /**
     * Get the current shift-lead for a hospital (empty if nobody holds the
     * badge yet).
     */
    @GetMapping("/hospital/{hospitalId}/shift-lead")
    public ResponseEntity<ApiResponse<ShiftAssignmentResponse>> getCurrentShiftLead(
            @PathVariable UUID hospitalId) {
        return shiftAssignmentService.getCurrentShiftLead(hospitalId)
                .map(lead -> ResponseEntity.ok(ApiResponse.success(lead)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.success("No active shift lead", null)));
    }

    /**
     * Transfer the shift-lead badge to a specific existing assignment. Only
     * callable by someone who already has authority on the target shift
     * (current lead, admins, or previous lead in grace window).
     */
    @PostMapping("/{assignmentId}/shift-lead")
    @PreAuthorize("@shiftAssignmentAuthz.canAssignForAssignment(authentication, #assignmentId)")
    public ResponseEntity<ApiResponse<ShiftAssignmentResponse>> setShiftLead(
            @PathVariable UUID assignmentId) {
        ShiftAssignmentResponse response = shiftAssignmentService.setShiftLead(assignmentId);
        return ResponseEntity.ok(ApiResponse.success("Shift-lead badge transferred", response));
    }

    /* ════════════════════════ BULK PLANNING OPS ════════════════════════ */

    /**
     * Copy a full week of active assignments into another week, preserving
     * (zone, function, period, isShiftLead, day-offset). Idempotent per
     * slot — any target (date, period) that already has rows is skipped.
     * The response reports per-slot outcomes so the UI can show partial
     * success.
     */
    @PostMapping("/hospital/{hospitalId}/copy-week")
    @PreAuthorize("@shiftAssignmentAuthz.canAssign(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<BulkPlanResult>> copyWeek(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody CopyWeekRequest request) {
        BulkPlanResult result = shiftPlanningService.copyWeek(hospitalId, request);
        return ResponseEntity.ok(ApiResponse.success("Week copied", result));
    }

    /**
     * Materialise a specific template into every (date, period) in the
     * supplied range. Idempotent per slot. Useful when a CN edits a
     * template and wants the next N days to reflect it immediately
     * instead of waiting for the daily scheduler.
     */
    @PostMapping("/hospital/{hospitalId}/apply-template")
    @PreAuthorize("@shiftAssignmentAuthz.canAssign(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<BulkPlanResult>> applyTemplate(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody ApplyTemplateRequest request) {
        BulkPlanResult result = shiftPlanningService.applyTemplate(hospitalId, request);
        return ResponseEntity.ok(ApiResponse.success("Template applied", result));
    }
}
