package com.smartTriage.smartTriage_server.module.governance.dto;

import com.smartTriage.smartTriage_server.common.enums.PolicyStatus;
import com.smartTriage.smartTriage_server.common.enums.PolicyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for clinical governance policy data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClinicalPolicyResponse {

    private UUID id;
    private UUID hospitalId;
    private String hospitalName;

    private PolicyType policyType;
    private String policyName;
    private String policyCode;
    private String description;

    private String policyContent;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private String policyVersion;

    private PolicyStatus status;
    private String createdByName;
    private String approvedByName;
    private Instant approvedAt;
    private String approvalNotes;

    private UUID previousVersionId;
    private String changeReason;
    private String notes;

    private Instant createdAt;
    private Instant updatedAt;
}
