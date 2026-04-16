package com.smartTriage.smartTriage_server.module.invitation.dto;

import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO for the invitation flow — admin only provides email, role, and hospital.
 * The invited user completes the rest during activation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteUserRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotNull(message = "Role is required")
    private Role role;

    private Designation designation;

    private String department;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;
}
