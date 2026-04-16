package com.smartTriage.smartTriage_server.module.governance.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for approving a clinical governance policy.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovePolicyRequest {

    @NotBlank(message = "Approver name is required")
    private String approverName;

    private String notes;
}
