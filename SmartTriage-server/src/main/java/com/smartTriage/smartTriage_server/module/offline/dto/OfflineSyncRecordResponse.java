package com.smartTriage.smartTriage_server.module.offline.dto;

import com.smartTriage.smartTriage_server.common.enums.SyncOperationType;
import com.smartTriage.smartTriage_server.common.enums.SyncStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for an offline sync record.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineSyncRecordResponse {

    private UUID id;
    private UUID hospitalId;
    private String clientDeviceId;
    private String clientDeviceName;
    private String entityType;
    private UUID entityId;
    private SyncOperationType operationType;
    private String payload;
    private SyncStatus syncStatus;
    private String conflictResolution;
    private Instant createdOfflineAt;
    private Instant syncedAt;
    private String errorMessage;
    private Instant createdAt;
}
