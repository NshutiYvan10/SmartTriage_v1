package com.smartTriage.smartTriage_server.module.iot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * The latest device-keyed vitals snapshot a paramedic pulls into the EMS
 * field-vitals form (V98). {@code ageSeconds} lets the UI warn on a stale
 * reading ("captured 6 min ago"); {@code hasReading} is false when the device
 * has never reported.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLatestVitalsResponse {
    private UUID deviceId;
    private String deviceName;
    private boolean hasReading;
    private Integer heartRate;
    private Integer respiratoryRate;
    private Integer spo2;
    private Integer systolicBp;
    private Integer diastolicBp;
    private Double temperature;
    private Double glucose;
    private Instant recordedAt;
    private Long ageSeconds;
}
