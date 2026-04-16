package com.smartTriage.smartTriage_server.module.vital.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.VitalSource;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * VitalSigns entity — captures a snapshot of patient vital signs at a point in time.
 * Multiple vitals records exist per visit (continuous monitoring).
 *
 * This is the primary data input for TEWS calculation and deterioration detection.
 * Vitals can come from manual entry or IoT medical devices.
 */
@Entity
@Table(name = "vital_signs", indexes = {
        @Index(name = "idx_vital_visit", columnList = "visit_id"),
        @Index(name = "idx_vital_recorded_at", columnList = "recorded_at"),
        @Index(name = "idx_vital_source", columnList = "source"),
        @Index(name = "idx_vital_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VitalSigns extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    // --- Core Vitals ---

    @Column(name = "respiratory_rate")
    private Integer respiratoryRate; // breaths per minute

    @Column(name = "heart_rate")
    private Integer heartRate; // beats per minute (pulse)

    @Column(name = "systolic_bp")
    private Integer systolicBp; // mmHg

    @Column(name = "diastolic_bp")
    private Integer diastolicBp; // mmHg

    @Column(name = "temperature")
    private Double temperature; // °C

    @Column(name = "spo2")
    private Integer spo2; // percentage

    @Enumerated(EnumType.STRING)
    @Column(name = "avpu", length = 15)
    private AvpuScore avpu;

    // --- Supplementary Vitals ---

    @Column(name = "blood_glucose")
    private Double bloodGlucose; // mmol/L

    @Column(name = "pain_score")
    private Integer painScore; // 0-10

    @Column(name = "gcs_score")
    private Integer gcsScore; // Glasgow Coma Scale 3-15

    // --- Data Source ---

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private VitalSource source = VitalSource.MANUAL_ENTRY;

    @Column(name = "device_id", length = 50)
    private String deviceId; // IoT device identifier

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
