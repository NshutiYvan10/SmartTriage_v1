package com.smartTriage.smartTriage_server.module.governance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for policy audit log entries.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyAuditLogResponse {

    private UUID id;
    private UUID policyId;
    private String action;
    private Instant actionAt;
    private String actionByName;
    private String previousContent;
    private String newContent;
    private String reason;
}
