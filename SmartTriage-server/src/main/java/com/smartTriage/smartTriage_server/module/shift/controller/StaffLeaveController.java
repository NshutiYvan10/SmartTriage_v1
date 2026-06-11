package com.smartTriage.smartTriage_server.module.shift.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.shift.dto.StaffLeaveDtos;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentAuthz;
import com.smartTriage.smartTriage_server.module.shift.service.StaffLeaveService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REST surface for {@link com.smartTriage.smartTriage_server.module.shift.entity.StaffLeave}.
 *
 * <p>Authorization model:
 * <ul>
 *   <li>Self-service create / cancel: any authenticated user, scoped to
 *       their own row.</li>
 *   <li>Filing on behalf of others, approving, rejecting, listing
 *       hospital-wide: gated by {@code @shiftAssignmentAuthz.canAssign} —
 *       same predicate as zone assignment, since both are the CN's
 *       day-to-day staffing remit.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/shifts/leaves")
@RequiredArgsConstructor
public class StaffLeaveController {

    private final StaffLeaveService leaveService;
    private final ShiftAssignmentAuthz shiftAssignmentAuthz;

    /**
     * Create a leave request. Self-service (omit {@code userId}) or on
     * behalf of another user (CN/admin authority required at this
     * hospital).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<StaffLeaveDtos.Response>> create(
            @Valid @RequestBody StaffLeaveDtos.CreateRequest request) {
        User actor = currentUser();
        if (actor == null) {
            throw new ClinicalBusinessException("Authentication required");
        }
        if (actor.getHospital() == null) {
            throw new ClinicalBusinessException(
                    "Cannot file leave: actor is not attached to a hospital");
        }
        boolean approvalAuthority = shiftAssignmentAuthz.canAssign(
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication(),
                actor.getHospital().getId());

        StaffLeaveDtos.Response response = leaveService.create(actor, request, approvalAuthority);
        return ResponseEntity.ok(ApiResponse.success("Leave recorded", response));
    }

    /**
     * Approve a pending leave request. Authority gate is the same chain as
     * shift assignment — the leave belongs to a hospital implicitly via
     * the leave row, so we resolve it inside the service after loading.
     */
    @PostMapping("/{leaveId}/approve")
    @PreAuthorize("@shiftAssignmentAuthz.canAssignForLeave(authentication, #leaveId)")
    public ResponseEntity<ApiResponse<StaffLeaveDtos.Response>> approve(
            @PathVariable UUID leaveId,
            @RequestBody(required = false) StaffLeaveDtos.DecisionRequest request) {
        User actor = currentUser();
        return ResponseEntity.ok(ApiResponse.success(
                "Leave approved", leaveService.approve(leaveId, actor, request)));
    }

    @PostMapping("/{leaveId}/reject")
    @PreAuthorize("@shiftAssignmentAuthz.canAssignForLeave(authentication, #leaveId)")
    public ResponseEntity<ApiResponse<StaffLeaveDtos.Response>> reject(
            @PathVariable UUID leaveId,
            @Valid @RequestBody StaffLeaveDtos.DecisionRequest request) {
        User actor = currentUser();
        return ResponseEntity.ok(ApiResponse.success(
                "Leave rejected", leaveService.reject(leaveId, actor, request)));
    }

    @PostMapping("/{leaveId}/cancel")
    public ResponseEntity<ApiResponse<StaffLeaveDtos.Response>> cancel(@PathVariable UUID leaveId) {
        User actor = currentUser();
        boolean approvalAuthority = actor.getHospital() != null
                && shiftAssignmentAuthz.canAssign(
                        SecurityContextHolder.getContext().getAuthentication(),
                        actor.getHospital().getId());
        return ResponseEntity.ok(ApiResponse.success(
                "Leave cancelled", leaveService.cancel(leaveId, actor, approvalAuthority)));
    }

    /** Pending-approval queue for a hospital. Read for HA + CN; only CN can decide. */
    @GetMapping("/hospital/{hospitalId}/pending")
    @PreAuthorize("@shiftAssignmentAuthz.canViewShift(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<StaffLeaveDtos.Response>>> listPending(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.listPending(hospitalId)));
    }

    /** Coverage feed for the calendar view. */
    @GetMapping("/hospital/{hospitalId}/overlapping")
    @PreAuthorize("@shiftAssignmentAuthz.canViewShift(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<StaffLeaveDtos.Response>>> listOverlapping(
            @PathVariable UUID hospitalId,
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
                leaveService.listOverlapping(hospitalId, from, to)));
    }

    /** All leave for a specific user (history view). */
    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<StaffLeaveDtos.Response>>> listForUser(
            @PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.listForUser(userId)));
    }

    /** Self-service: my own leave history. */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<StaffLeaveDtos.Response>>> listMine() {
        User actor = currentUser();
        return ResponseEntity.ok(ApiResponse.success(leaveService.listForUser(actor.getId())));
    }

    private User currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof User u ? u : null;
    }
}
