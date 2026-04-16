package com.smartTriage.smartTriage_server.module.governance.dto;

import com.smartTriage.smartTriage_server.common.enums.PolicyType;
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

    private String policyName;

    private String policyCode;

    private String description;

    private String policyContent;

    private Instant effectiveFrom;

    private Instant effectiveTo;

    private String policyVersion;

    private String changeReason;

    private String notes;
}
