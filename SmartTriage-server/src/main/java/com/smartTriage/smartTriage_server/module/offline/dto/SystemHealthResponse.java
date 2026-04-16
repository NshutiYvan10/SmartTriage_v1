package com.smartTriage.smartTriage_server.module.offline.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for system health status.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemHealthResponse {

    private UUID id;
    private UUID hospitalId;
    private String hospitalName;
    private Instant checkTime;
    private boolean serverOnline;
    private boolean databaseOnline;
    private boolean internetConnectivity;
    private String powerStatus;
    private Instant lastSuccessfulSync;
    private int pendingSyncCount;
    private int activeOfflineDevices;
    private String notes;
    private Instant createdAt;
}
