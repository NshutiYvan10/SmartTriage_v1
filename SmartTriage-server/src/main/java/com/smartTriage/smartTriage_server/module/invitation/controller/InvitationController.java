package com.smartTriage.smartTriage_server.module.invitation.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.invitation.dto.ActivateAccountRequest;
import com.smartTriage.smartTriage_server.module.invitation.dto.InviteUserRequest;
import com.smartTriage.smartTriage_server.module.invitation.service.InvitationService;
import com.smartTriage.smartTriage_server.module.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Invitation controller — endpoints for the invite/activate flow.
 *
 * POST /api/v1/users/invite       → Admin sends invitation (authenticated)
 * POST /api/v1/users/resend/{id}  → Admin resends invitation (authenticated)
 * GET  /api/v1/auth/validate-token → Validate invitation token (public)
 * POST /api/v1/auth/activate       → User activates account (public)
 */
@RestController
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    // ── Authenticated endpoints (admin) ──

    @PostMapping("/api/v1/users/invite")
    @PreAuthorize("@userAdminAuthz.canCreateUserWithRole(authentication, #request.role, #request.hospitalId)")
    public ResponseEntity<ApiResponse<UserResponse>> inviteUser(@Valid @RequestBody InviteUserRequest request) {
        UserResponse response = invitationService.inviteUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Invitation sent successfully", response));
    }

    @PostMapping("/api/v1/users/{id}/resend-invite")
    @PreAuthorize("@userAdminAuthz.canManageUser(authentication, #id)")
    public ResponseEntity<ApiResponse<Void>> resendInvitation(@PathVariable UUID id) {
        invitationService.resendInvitation(id);
        return ResponseEntity.ok(ApiResponse.success("Invitation resent successfully", null));
    }

    /**
     * Cancel a pending invitation. Soft-deletes the user and
     * invalidates any outstanding token so the email link stops
     * working immediately. Only valid for PENDING_ACTIVATION;
     * already-activated users use the regular deactivate endpoint
     * which has different cleanup semantics.
     */
    @DeleteMapping("/api/v1/users/{id}/invite")
    @PreAuthorize("@userAdminAuthz.canManageUser(authentication, #id)")
    public ResponseEntity<ApiResponse<Void>> cancelInvitation(@PathVariable UUID id) {
        invitationService.cancelInvitation(id);
        return ResponseEntity.ok(ApiResponse.success("Invitation cancelled", null));
    }

    // ── Public endpoints (no authentication required) ──

    @GetMapping("/api/v1/auth/validate-token")
    public ResponseEntity<ApiResponse<InvitationService.InvitationTokenInfo>> validateToken(
            @RequestParam String token) {
        InvitationService.InvitationTokenInfo info = invitationService.validateToken(token);
        return ResponseEntity.ok(ApiResponse.success(info));
    }

    @PostMapping("/api/v1/auth/activate")
    public ResponseEntity<ApiResponse<UserResponse>> activateAccount(
            @Valid @RequestBody ActivateAccountRequest request) {
        UserResponse response = invitationService.activateAccount(request);
        return ResponseEntity.ok(ApiResponse.success("Account activated successfully", response));
    }
}
