package com.smartTriage.smartTriage_server.module.governance.dto;

import com.smartTriage.smartTriage_server.common.enums.PolicyType;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Request DTO for updating an existing clinical governance policy.
 * Only draft policies can be updated.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdatePolicyRequest {

    private PolicyType policyType;

    @Size(max = 255, message = "Policy name must be at most 255 characters")
    private String policyName;

    @Size(max = 50, message = "Policy code must be at most 50 characters")
    private String policyCode;

    private String description;

    private String policyContent;

    private Instant effectiveFrom;

    private Instant effectiveTo;

    @Size(max = 20, message = "Policy version must be at most 20 characters")
    private String policyVersion;

    private String changeReason;

    private String notes;
}
