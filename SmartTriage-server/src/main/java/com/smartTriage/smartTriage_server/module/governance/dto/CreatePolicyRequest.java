package com.smartTriage.smartTriage_server.module.governance.dto;

import com.smartTriage.smartTriage_server.common.enums.PolicyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO for creating a new clinical governance policy.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePolicyRequest {

    private UUID hospitalId;

    @NotNull(message = "Policy type is required")
    private PolicyType policyType;

    @NotBlank(message = "Policy name is required")
    private String policyName;

    private String policyCode;

    private String description;

    @NotBlank(message = "Policy content is required")
    private String policyContent;

    @NotNull(message = "Effective from date is required")
    private Instant effectiveFrom;

    private Instant effectiveTo;

    private String policyVersion;

    private String createdByName;

    private String changeReason;

    private String notes;
}
