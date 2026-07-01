package com.smartTriage.smartTriage_server.module.iot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A device-keyed vitals snapshot posted by a self-registered monitor (V98).
 *
 * <p>Unlike {@code VitalStreamPayload} (which flows through a visit-bound
 * session into the ED bedside-monitoring pipeline), this is a plain latest
 * reading stored ON THE DEVICE so a paramedic can "pull from my monitor" into
 * the EMS field-vitals before any hospital Visit exists. All fields optional —
 * a device sends whatever it measures.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceTelemetryRequest {
    private Integer heartRate;
    private Integer respiratoryRate;
    private Integer spo2;
    private Integer systolicBp;
    private Integer diastolicBp;
    private Double temperature;
    private Double glucose;
}
