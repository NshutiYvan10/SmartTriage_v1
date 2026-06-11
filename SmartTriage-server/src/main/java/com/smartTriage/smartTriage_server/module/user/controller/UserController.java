package com.smartTriage.smartTriage_server.module.user.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.user.dto.CreateUserRequest;
import com.smartTriage.smartTriage_server.module.user.dto.UpdateUserRequest;
import com.smartTriage.smartTriage_server.module.user.dto.UserResponse;
import com.smartTriage.smartTriage_server.module.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User management endpoints.
 * User creation restricted to SUPER_ADMIN and HOSPITAL_ADMIN roles.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    @PreAuthorize("@userAdminAuthz.canCreateUserWithRole(authentication, #request.role, #request.hospitalId)")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created successfully", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUser(@PathVariable UUID id) {
        UserResponse response = userService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}")
    // Authz sweep — staff directory is hospital-scoped. Same-hospital staff
    // (any role) keep access: the shift planner staff pool and triage-nurse
    // pickers depend on it. Cross-hospital PII reads are now denied.
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<UserResponse>>> getUsersByHospital(
            @PathVariable UUID hospitalId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<UserResponse> response = userService.getUsersByHospital(hospitalId, includeInactive, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@userAdminAuthz.canManageUser(authentication, #id)")
    public ResponseEntity<ApiResponse<Void>> deactivateUser(@PathVariable UUID id) {
        userService.deactivateUser(id);
        return ResponseEntity.ok(ApiResponse.success("User deactivated", null));
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("@userAdminAuthz.canManageUser(authentication, #id)")
    public ResponseEntity<ApiResponse<Void>> reactivateUser(@PathVariable UUID id) {
        userService.reactivateUser(id);
        return ResponseEntity.ok(ApiResponse.success("User reactivated", null));
    }

    /**
     * Update a user (admin edit).
     *
     * <p>Authorization splits the request body into two zones:
     * personal-info fields (firstName, lastName, email, phoneNumber,
     * professionalLicense) require {@code canEditUserPersonalInfo};
     * governance fields (role, designation, hospital, accountStatus)
     * require {@code canManageUser}. The endpoint gate here is the
     * looser of the two — the service enforces the per-field policy
     * by inspecting which fields are non-null on the request and
     * checking the strict gate before applying personal-info edits.
     * Today the looser gate is canManageUser, so we use that here;
     * the service-level enforcement is the actual safety guarantee.
     */
    @PutMapping("/{id}")
    @PreAuthorize("@userAdminAuthz.canManageUser(authentication, #id)")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        UserResponse response = userService.updateUser(id, request);
        return ResponseEntity.ok(ApiResponse.success("User updated", response));
    }

    /**
     * Update just a user's designation (professional title). Allowed
     * for any actor with manage-authority over the target.
     */
    @PatchMapping("/{id}/designation")
    @PreAuthorize("@userAdminAuthz.canManageUser(authentication, #id)")
    public ResponseEntity<ApiResponse<UserResponse>> updateDesignation(
            @PathVariable UUID id, @RequestBody Map<String, String> body) {
        Designation designation = Designation.valueOf(body.get("designation"));
        UserResponse response = userService.updateDesignation(id, designation);
        return ResponseEntity.ok(ApiResponse.success("Designation updated", response));
    }

    /**
     * Get the list of valid designations, optionally filtered by role.
     * Used by the admin UI to populate the designation dropdown.
     */
    @GetMapping("/designations")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getDesignations(
            @RequestParam(required = false) Role role) {
        Designation[] designations = role != null
                ? Designation.forRole(role)
                : Designation.values();
        List<Map<String, String>> result = Arrays.stream(designations)
                .map(d -> Map.of("value", d.name(), "label", d.getLabel()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
