package com.smartTriage.smartTriage_server.module.iot.dto;

import com.smartTriage.smartTriage_server.common.enums.SignalQuality;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a vital stream reading.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VitalStreamResponse {

    private UUID id;
    private UUID visitId;
    private String deviceId;
    private UUID sessionId;
    private Instant capturedAt;
    private Instant receivedAt;

    // Vitals
    private Integer heartRate;
    private Integer spo2;
    private Integer respiratoryRate;
    private Double temperature;
    private Integer systolicBp;
    private Integer diastolicBp;
    private Double bloodGlucose;

    // ECG
    private String ecgRhythm;
    private Integer ecgQrsDuration;
    private Double ecgStDeviation;

    // Quality
    private SignalQuality signalQuality;
    private Double spo2PerfusionIndex;
    private boolean isValidated;
    private String rejectionReason;

    // Device metadata
    private Integer batteryLevel;
    private Integer wifiRssi;
    private Long sequenceNumber;
}
