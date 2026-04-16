package com.smartTriage.smartTriage_server.module.governance.mapper;

import com.smartTriage.smartTriage_server.module.governance.dto.PolicyAuditLogResponse;
import com.smartTriage.smartTriage_server.module.governance.entity.PolicyAuditLog;

/**
 * Mapper for PolicyAuditLog entity to response DTO.
 */
public final class PolicyAuditLogMapper {

    private PolicyAuditLogMapper() {
    }

    public static PolicyAuditLogResponse toResponse(PolicyAuditLog auditLog) {
        return PolicyAuditLogResponse.builder()
                .id(auditLog.getId())
                .policyId(auditLog.getPolicy() != null ? auditLog.getPolicy().getId() : null)
                .action(auditLog.getAction())
                .actionAt(auditLog.getActionAt())
                .actionByName(auditLog.getActionByName())
                .previousContent(auditLog.getPreviousContent())
                .newContent(auditLog.getNewContent())
                .reason(auditLog.getReason())
                .build();
    }
}
