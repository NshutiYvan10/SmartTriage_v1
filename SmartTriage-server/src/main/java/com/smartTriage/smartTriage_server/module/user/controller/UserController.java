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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
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
    public ResponseEntity<ApiResponse<Page<UserResponse>>> getUsersByHospital(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<UserResponse> response = userService.getUsersByHospital(hospitalId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivateUser(@PathVariable UUID id) {
        userService.deactivateUser(id);
        return ResponseEntity.ok(ApiResponse.success("User deactivated", null));
    }

    /**
     * Update a user (admin edit).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        UserResponse response = userService.updateUser(id, request);
        return ResponseEntity.ok(ApiResponse.success("User updated", response));
    }

    /**
     * Update just a user's designation (professional title).
     * Only SUPER_ADMIN and HOSPITAL_ADMIN can change designations.
     */
    @PatchMapping("/{id}/designation")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
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
