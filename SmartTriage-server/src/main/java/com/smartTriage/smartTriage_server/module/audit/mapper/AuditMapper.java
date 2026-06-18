package com.smartTriage.smartTriage_server.module.audit.mapper;

import com.smartTriage.smartTriage_server.module.audit.dto.AuditLogResponse;
import com.smartTriage.smartTriage_server.module.audit.entity.AuditLog;

public final class AuditMapper {

    private AuditMapper() {}

    public static AuditLogResponse toResponse(AuditLog a) {
        return AuditLogResponse.builder()
                .id(a.getId())
                .timestamp(a.getCreatedAt())
                .actorUserId(a.getActorUserId())
                .actorName(a.getActorName())
                .actorRole(a.getActorRole())
                .hospitalId(a.getHospitalId())
                .httpMethod(a.getHttpMethod())
                .path(a.getPath())
                .action(a.getAction())
                .statusCode(a.getStatusCode())
                .outcome(a.getOutcome())
                .build();
    }
}
