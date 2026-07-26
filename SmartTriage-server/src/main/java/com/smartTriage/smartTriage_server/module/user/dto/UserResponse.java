package com.smartTriage.smartTriage_server.module.user.dto;

import com.smartTriage.smartTriage_server.common.enums.AccountStatus;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private UUID id;
    private String firstName;
    private String lastName;
    private String email;
    private String phoneNumber;
    private Role role;
    private Designation designation;
    private String designationLabel;
    private String employeeNumber;
    private String professionalLicense;
    private String department;
    private UUID hospitalId;
    private String hospitalName;
    private AccountStatus accountStatus;
    /**
     * Soft-delete flag. Needed by admin list views (which request
     * includeInactive=true) to tell a CANCELLED invitation — soft-deleted
     * while still PENDING_ACTIVATION — apart from a live pending one.
     * Without it, cancelled invites rendered as still-pending rows and
     * "cancel invitation" looked like a no-op.
     */
    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
}
