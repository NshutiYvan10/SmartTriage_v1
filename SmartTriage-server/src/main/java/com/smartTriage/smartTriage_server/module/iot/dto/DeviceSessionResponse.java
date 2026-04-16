package com.smartTriage.smartTriage_server.module.iot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a device monitoring session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceSessionResponse {

    private UUID id;
    private UUID deviceId;
    private String deviceName;
    private String deviceSerialNumber;
    private UUID visitId;
    private String visitNumber;
    private Instant startedAt;
    private Instant endedAt;
    private boolean sessionActive;
    private String startedByName;
    private String endedByName;
    private String endReason;
    private long totalReadings;
    private long rejectedReadings;
    private int alertsGenerated;
    private int retriagesTriggered;
    private Instant createdAt;
}
