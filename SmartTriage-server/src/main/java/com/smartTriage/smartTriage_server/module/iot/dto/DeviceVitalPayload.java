package com.smartTriage.smartTriage_server.module.iot.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Vital data payload sent by the ESP32 IoT device.
 *
 * This is the wire-format for real-time vital streaming.
 * Devices send this payload at their configured data interval (default: 5s).
 *
 * The device authenticates via its API key in the request header.
 * The serial number in the payload is cross-checked against the API key's
 * device.
 *
 * Field semantics match the sensors on the ESP32 multi-parameter monitor:
 * - Heart Rate: derived from pulse oximeter or ECG R-R interval
 * - SpO2: from reflective/transmissive pulse oximetry
 * - Respiratory Rate: impedance pneumography or accelerometer
 * - Temperature: IR thermopile or contact NTC thermistor
 * - Blood Pressure: oscillometric measurement (when triggered)
 * - Blood Glucose: integrated glucometer (when triggered)
 * - ECG: Lead-II waveform from AD8232 or MAX30003
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceVitalPayload {

    /** Device serial number — must match authenticated device */
    @NotBlank(message = "Device serial number is required")
    private String serialNumber;

    /** Device-side timestamp (ISO-8601 or epoch millis) */
    private Instant capturedAt;

    /** Monotonically increasing sequence number for gap detection */
    private Long sequenceNumber;

    // ====================================================================
    // VITAL READINGS (all nullable — device sends whatever it has)
    // ====================================================================

    private Integer heartRate; // bpm
    private Integer spo2; // 0-100 %
    private Integer respiratoryRate; // breaths/min
    private Double temperature; // °C
    private Integer systolicBp; // mmHg
    private Integer diastolicBp; // mmHg
    private Double bloodGlucose; // mmol/L

    // ====================================================================
    // ECG (optional — only when ECG sensor is active)
    // ====================================================================

    /** Raw ECG waveform samples (comma-separated ADC values for one cycle) */
    private String ecgWaveform;

    /** On-device rhythm classification (if supported) */
    private String ecgRhythm;

    /** QRS duration in ms (if computed on-device) */
    private Integer ecgQrsDuration;

    /** ST-segment deviation in mV (positive = elevation, negative = depression) */
    private Double ecgStDeviation;

    // ====================================================================
    // DEVICE METADATA
    // ====================================================================

    /** Battery level 0-100 (null if wired power) */
    private Integer batteryLevel;

    /** WiFi signal strength in dBm */
    private Integer wifiRssi;

    /** SpO2 perfusion index — quality indicator */
    private Double spo2PerfusionIndex;

    /** Device firmware version (sent periodically, not every reading) */
    private String firmwareVersion;
}
