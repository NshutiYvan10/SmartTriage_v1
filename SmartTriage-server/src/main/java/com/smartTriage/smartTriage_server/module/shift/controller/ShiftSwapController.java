package com.smartTriage.smartTriage_server.module.shift.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftSwapDtos;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftSwapService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for the {@link com.smartTriage.smartTriage_server.module.shift.entity.ShiftSwapRequest}
 * workflow.
 *
 * <p>Endpoints are partitioned by the actor that the workflow requires:
 * <ul>
 *   <li>Proposer / partner / either-side endpoints — owned by participants;
 *       service-layer authorization checks the actor matches the row.</li>
 *   <li>Charge approve / reject — gated by {@code @shiftAssignmentAuthz.canAssignForSwap}.</li>
 *   <li>Listings — read-only, scoped to the authenticated user or hospital.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/shifts/swaps")
@RequiredArgsConstructor
public class ShiftSwapController {

    private final ShiftSwapService swapService;

    @PostMapping
    public ResponseEntity<ApiResponse<ShiftSwapDtos.Response>> propose(
            @Valid @RequestBody ShiftSwapDtos.CreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Swap proposed", swapService.propose(currentUser(), request)));
    }

    @PostMapping("/{swapId}/partner-accept")
    public ResponseEntity<ApiResponse<ShiftSwapDtos.Response>> partnerAccept(
            @PathVariable UUID swapId,
            @RequestBody(required = false) ShiftSwapDtos.DecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Swap accepted; awaiting Charge Nurse approval",
                swapService.partnerAccept(swapId, currentUser(), request)));
    }

    @PostMapping("/{swapId}/partner-reject")
    public ResponseEntity<ApiResponse<ShiftSwapDtos.Response>> partnerReject(
            @PathVariable UUID swapId,
            @RequestBody(required = false) ShiftSwapDtos.DecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Swap rejected by partner",
                swapService.partnerReject(swapId, currentUser(), request)));
    }

    @PostMapping("/{swapId}/cancel")
    public ResponseEntity<ApiResponse<ShiftSwapDtos.Response>> cancel(@PathVariable UUID swapId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Swap cancelled", swapService.cancel(swapId, currentUser())));
    }

    @PostMapping("/{swapId}/charge-approve")
    @PreAuthorize("@shiftAssignmentAuthz.canAssignForSwap(authentication, #swapId)")
    public ResponseEntity<ApiResponse<ShiftSwapDtos.Response>> chargeApprove(
            @PathVariable UUID swapId,
            @RequestBody(required = false) ShiftSwapDtos.DecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Swap approved and applied to roster",
                swapService.chargeApprove(swapId, currentUser(), request)));
    }

    @PostMapping("/{swapId}/charge-reject")
    @PreAuthorize("@shiftAssignmentAuthz.canAssignForSwap(authentication, #swapId)")
    public ResponseEntity<ApiResponse<ShiftSwapDtos.Response>> chargeReject(
            @PathVariable UUID swapId,
            @Valid @RequestBody ShiftSwapDtos.DecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Swap rejected by Charge Nurse",
                swapService.chargeReject(swapId, currentUser(), request)));
    }

    /** Open swaps the authenticated user is involved in (either side). */
    @GetMapping("/me/open")
    public ResponseEntity<ApiResponse<List<ShiftSwapDtos.Response>>> myOpen() {
        return ResponseEntity.ok(ApiResponse.success(
                swapService.listOpenForUser(currentUser().getId())));
    }

    @GetMapping("/me/history")
    public ResponseEntity<ApiResponse<List<ShiftSwapDtos.Response>>> myHistory() {
        return ResponseEntity.ok(ApiResponse.success(
                swapService.listHistoryForUser(currentUser().getId())));
    }

    /** CN approval queue at this hospital. */
    @GetMapping("/hospital/{hospitalId}/charge-queue")
    @PreAuthorize("@shiftAssignmentAuthz.canAssign(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<ShiftSwapDtos.Response>>> chargeQueue(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(
                swapService.listChargeQueue(hospitalId)));
    }

    private User currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof User u ? u : null;
    }
}
