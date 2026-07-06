package com.smartTriage.smartTriage_server.module.audit.dto;

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
public class AuditLogResponse {
    private UUID id;
    private Instant timestamp;
    private UUID actorUserId;
    private String actorName;
    private String actorRole;
    private UUID hospitalId;
    // Patient linkage (V107) — which visit/patient the action touched, when resolvable.
    private UUID visitId;
    private UUID patientId;
    /** Enriched for display (batch-resolved per page; null when the row has no visit). */
    private String visitNumber;
    private String patientName;
    private String httpMethod;
    private String path;
    private String action;
    private Integer statusCode;
    private String outcome;
    /** Request origin (V108 forensics). */
    private String sourceIp;
    private String userAgent;
}
