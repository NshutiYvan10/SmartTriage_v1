package com.smartTriage.smartTriage_server.module.offline.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response DTO for overall sync health status.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncStatusResponse {

    private UUID hospitalId;
    private long pendingCount;
    private long syncedCount;
    private long conflictCount;
    private long failedCount;
}
