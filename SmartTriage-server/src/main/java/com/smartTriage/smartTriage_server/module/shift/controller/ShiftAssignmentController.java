package com.smartTriage.smartTriage_server.module.shift.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.shift.dto.CreateShiftAssignmentRequest;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftAssignmentResponse;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
}
