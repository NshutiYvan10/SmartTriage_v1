package com.smartTriage.smartTriage_server.module.iot.dto;

import com.smartTriage.smartTriage_server.common.enums.MonitoringState;
import com.smartTriage.smartTriage_server.common.enums.TrendStatus;
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
    private TrendStatus trendStatus;
    private Instant trendUpdatedAt;
    /** Clinical-facing monitoring lifecycle state. */
    private MonitoringState monitoringState;
    /** Last state transition time, for UI freshness display. */
    private Instant monitoringStateAt;
    private Instant pausedAt;
    private String pausedByName;
    private Instant resumedAt;
    private String resumedByName;
    /** Groups split sessions (transferred patient) into one timeline. */
    private UUID continuityGroupId;
    private Instant createdAt;
}
