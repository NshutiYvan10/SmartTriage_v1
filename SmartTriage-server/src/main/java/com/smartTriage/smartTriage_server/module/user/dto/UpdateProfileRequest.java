package com.smartTriage.smartTriage_server.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Self-service profile edit — the fields a user may change about THEMSELVES
 * from the Profile page. Deliberately narrow: name + phone only. Identity
 * attributes that gate access or login (email, role, designation, hospital,
 * employee/licence numbers) are NOT self-editable — those remain admin-only via
 * {@link UpdateUserRequest} so a user cannot silently change who they are or
 * what they can do.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @NotBlank(message = "First name is required")
    @Size(max = 100)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    private String lastName;

    @Size(max = 20)
    private String phoneNumber;
}
