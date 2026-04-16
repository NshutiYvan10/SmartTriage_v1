package com.smartTriage.smartTriage_server.module.governance.mapper;

import com.smartTriage.smartTriage_server.module.governance.dto.ClinicalPolicyResponse;
import com.smartTriage.smartTriage_server.module.governance.entity.ClinicalPolicy;

/**
 * Mapper for ClinicalPolicy entity to response DTO.
 */
public final class ClinicalPolicyMapper {

    private ClinicalPolicyMapper() {
    }

    public static ClinicalPolicyResponse toResponse(ClinicalPolicy policy) {
        ClinicalPolicyResponse.ClinicalPolicyResponseBuilder builder = ClinicalPolicyResponse.builder()
                .id(policy.getId())
                .policyType(policy.getPolicyType())
                .policyName(policy.getPolicyName())
                .policyCode(policy.getPolicyCode())
                .description(policy.getDescription())
                .policyContent(policy.getPolicyContent())
                .effectiveFrom(policy.getEffectiveFrom())
                .effectiveTo(policy.getEffectiveTo())
                .policyVersion(policy.getPolicyVersion())
                .status(policy.getStatus())
                .createdByName(policy.getCreatedByName())
                .approvedByName(policy.getApprovedByName())
                .approvedAt(policy.getApprovedAt())
                .approvalNotes(policy.getApprovalNotes())
                .changeReason(policy.getChangeReason())
                .notes(policy.getNotes())
                .createdAt(policy.getCreatedAt())
                .updatedAt(policy.getUpdatedAt());

        if (policy.getHospital() != null) {
            builder.hospitalId(policy.getHospital().getId());
            builder.hospitalName(policy.getHospital().getName());
        }

        if (policy.getPreviousVersion() != null) {
            builder.previousVersionId(policy.getPreviousVersion().getId());
        }

        return builder.build();
    }
}
