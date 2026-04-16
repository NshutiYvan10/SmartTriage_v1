package com.smartTriage.smartTriage_server.module.governance.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for suspending an active clinical governance policy.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuspendPolicyRequest {

    @NotBlank(message = "Suspension reason is required")
    private String reason;
}
