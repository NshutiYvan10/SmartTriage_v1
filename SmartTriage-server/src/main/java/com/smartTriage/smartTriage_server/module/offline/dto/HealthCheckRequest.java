package com.smartTriage.smartTriage_server.module.offline.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO for recording a system health check.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthCheckRequest {

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    private boolean serverOnline;
    private boolean databaseOnline;
    private boolean internetConnectivity;
    private String powerStatus;
    private Instant lastSuccessfulSync;
    private int pendingSyncCount;
    private int activeOfflineDevices;
    private String notes;
}
