package com.smartTriage.smartTriage_server.module.shift.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.shift.dto.ChargeNurseDelegationDtos;
import com.smartTriage.smartTriage_server.module.shift.service.ChargeNurseDelegationService;
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
 * REST surface for {@link com.smartTriage.smartTriage_server.module.shift.entity.ChargeNurseDelegation}
 * management.
 *
 * <p>Authority follows the same {@code @shiftAssignmentAuthz} chain as the
 * core shift APIs — a CN delegating authority to a deputy is a shift-staffing
 * decision, not an admin-plane action.
 */
@RestController
@RequestMapping("/api/v1/shifts/delegations")
@RequiredArgsConstructor
public class ChargeNurseDelegationController {

    private final ChargeNurseDelegationService delegationService;

    /**
     * Create a new acting-CN delegation. The delegating user is taken from
     * the authenticated principal so the audit trail can never be forged.
     */
    @PostMapping("/hospital/{hospitalId}")
    @PreAuthorize("@shiftAssignmentAuthz.canAssign(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<ChargeNurseDelegationDtos.Response>> create(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody ChargeNurseDelegationDtos.CreateRequest request) {
        User actor = currentUser();
        ChargeNurseDelegationDtos.Response response =
                delegationService.create(hospitalId, actor, request);
        return ResponseEntity.ok(ApiResponse.success("Delegation created", response));
    }

    /**
     * Revoke an existing delegation early. The service enforces that only
     * the original CN, the delegate, or an admin may revoke.
     */
    @PostMapping("/{delegationId}/revoke")
    public ResponseEntity<ApiResponse<ChargeNurseDelegationDtos.Response>> revoke(
            @PathVariable UUID delegationId,
            @RequestBody(required = false) ChargeNurseDelegationDtos.RevokeRequest request) {
        User actor = currentUser();
        ChargeNurseDelegationDtos.Response response =
                delegationService.revoke(delegationId, actor, request);
        return ResponseEntity.ok(ApiResponse.success("Delegation revoked", response));
    }

    /** All delegations currently in effect at a hospital. */
    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<ChargeNurseDelegationDtos.Response>>> listActive(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(delegationService.listActive(hospitalId)));
    }

    /** Delegations issued by the authenticated user (their CN history). */
    @GetMapping("/me/issued")
    public ResponseEntity<ApiResponse<List<ChargeNurseDelegationDtos.Response>>> listMyIssued() {
        User actor = currentUser();
        return ResponseEntity.ok(ApiResponse.success(delegationService.listIssuedBy(actor.getId())));
    }

    /** Delegations the authenticated user has been the acting CN for. */
    @GetMapping("/me/received")
    public ResponseEntity<ApiResponse<List<ChargeNurseDelegationDtos.Response>>> listMyReceived() {
        User actor = currentUser();
        return ResponseEntity.ok(ApiResponse.success(delegationService.listReceivedBy(actor.getId())));
    }

    private User currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof User u ? u : null;
    }
}
