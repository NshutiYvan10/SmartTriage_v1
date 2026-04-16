package com.smartTriage.smartTriage_server.module.iot.dto;

import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.DeviceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for an IoT device.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceResponse {

    private UUID id;
    private String serialNumber;
    private String deviceName;
    private DeviceType deviceType;
    private UUID hospitalId;
    private DeviceStatus status;
    private String firmwareVersion;
    private Instant lastHeartbeatAt;
    private Instant lastDataAt;
    private Integer batteryLevel;
    private Integer wifiRssi;
    private String ipAddress;
    private String macAddress;
    private String location;
    private int heartbeatTimeoutSeconds;
    private int dataIntervalSeconds;
    private String notes;

    /** API key — only populated on device registration response */
    private String apiKey;

    /** The active session's visit ID, if device is currently monitoring */
    private UUID activeVisitId;

    private Instant createdAt;
    private Instant updatedAt;
}
