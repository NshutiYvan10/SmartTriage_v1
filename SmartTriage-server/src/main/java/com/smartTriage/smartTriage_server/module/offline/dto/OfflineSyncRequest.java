package com.smartTriage.smartTriage_server.module.offline.dto;

import com.smartTriage.smartTriage_server.common.enums.SyncOperationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO for a single offline sync record.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineSyncRequest {

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @NotBlank(message = "Client device ID is required")
    private String clientDeviceId;

    private String clientDeviceName;

    @NotBlank(message = "Entity type is required")
    private String entityType;

    private UUID entityId;

    @NotNull(message = "Operation type is required")
    private SyncOperationType operationType;

    @NotBlank(message = "Payload is required")
    private String payload;

    private Instant createdOfflineAt;
}
