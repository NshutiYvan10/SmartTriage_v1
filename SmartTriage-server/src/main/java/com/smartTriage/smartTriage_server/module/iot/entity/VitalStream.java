package com.smartTriage.smartTriage_server.module.iot.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.SignalQuality;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * VitalStream — high-frequency time-series data from IoT devices.
 *
 * This is SEPARATE from VitalSigns (which is a validated clinical snapshot).
 * VitalStream captures raw, continuous readings at the device's sampling rate
 * (typically every 1-5 seconds).
 *
 * Design rationale for a separate table:
 * - VitalSigns: Low-frequency (minutes apart), validated, used for TEWS
 * - VitalStream: High-frequency (seconds apart), raw, used for trend analysis
 *
 * The AI monitoring engine reads from VitalStream to detect deterioration
 * patterns. When a validated reading is needed (for TEWS or clinical snapshot),
 * the system aggregates recent VitalStream readings into a VitalSigns record.
 *
 * At 5-second intervals, a single patient generates ~17,280 readings/day.
 * This table is designed for time-series query patterns with partitioning
 * and TTL-based archival in production.
 */
@Entity
@Table(name = "vital_streams", indexes = {
        @Index(name = "idx_vs_visit", columnList = "visit_id"),
        @Index(name = "idx_vs_device", columnList = "device_id"),
        @Index(name = "idx_vs_session", columnList = "session_id"),
        @Index(name = "idx_vs_timestamp", columnList = "captured_at"),
        @Index(name = "idx_vs_visit_time", columnList = "visit_id, captured_at"),
        @Index(name = "idx_vs_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VitalStream extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    @Column(name = "session_id")
    private java.util.UUID sessionId;

    /** Device-side timestamp (when sensor captured the reading) */
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    /** Server-side timestamp (when backend received the reading) */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    // ====================================================================
    // RAW VITAL READINGS
    // ====================================================================

    /** Heart rate (bpm) — from pulse oximeter or ECG R-R interval */
    @Column(name = "heart_rate")
    private Integer heartRate;

    /** SpO2 (%) — from pulse oximeter */
    @Column(name = "spo2")
    private Integer spo2;

    /**
     * Respiratory rate (breaths/min) — from impedance pneumography or accelerometer
     */
    @Column(name = "respiratory_rate")
    private Integer respiratoryRate;

    /** Temperature (°C) — from IR or contact thermistor */
    @Column(name = "temperature")
    private Double temperature;

    /** Systolic blood pressure (mmHg) — from oscillometric cuff */
    @Column(name = "systolic_bp")
    private Integer systolicBp;

    /** Diastolic blood pressure (mmHg) */
    @Column(name = "diastolic_bp")
    private Integer diastolicBp;

    /** Blood glucose (mmol/L) — from glucometer integration */
    @Column(name = "blood_glucose")
    private Double bloodGlucose;

    // ====================================================================
    // ECG DATA (compact representation)
    // ====================================================================

    /**
     * ECG lead-II raw waveform — stored as comma-separated values for a sample
     * window
     */
    @Column(name = "ecg_waveform", columnDefinition = "TEXT")
    private String ecgWaveform;

    /** ECG heart rhythm classification (e.g., "NSR", "AF", "SVT", "VF") */
    @Column(name = "ecg_rhythm", length = 30)
    private String ecgRhythm;

    /** QRS duration (ms) */
    @Column(name = "ecg_qrs_duration")
    private Integer ecgQrsDuration;

    /** ST-segment deviation (mV) — positive = elevation, negative = depression */
    @Column(name = "ecg_st_deviation")
    private Double ecgStDeviation;

    // ====================================================================
    // SIGNAL QUALITY & METADATA
    // ====================================================================

    /** Overall signal quality assessment */
    @Enumerated(EnumType.STRING)
    @Column(name = "signal_quality", nullable = false, length = 15)
    @Builder.Default
    private SignalQuality signalQuality = SignalQuality.UNKNOWN;

    /** SpO2 perfusion index (PI) — quality indicator for pulse ox readings */
    @Column(name = "spo2_perfusion_index")
    private Double spo2PerfusionIndex;

    /** Whether this reading passed validation and is clinically usable */
    @Column(name = "is_validated", nullable = false)
    @Builder.Default
    private boolean isValidated = false;

    /** If not validated, the reason for rejection */
    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    /** Battery level at time of reading */
    @Column(name = "battery_level")
    private Integer batteryLevel;

    /** WiFi RSSI at time of reading */
    @Column(name = "wifi_rssi")
    private Integer wifiRssi;

    /** Sequence number from the device (for gap detection) */
    @Column(name = "sequence_number")
    private Long sequenceNumber;
}
