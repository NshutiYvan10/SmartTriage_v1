package com.smartTriage.smartTriage_server.module.iot.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.TrendStatus;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * DeviceSession — links an IoT device to a patient's active visit for continuous monitoring.
 *
 * Represents the time window during which a specific device is assigned to and
 * actively monitoring a specific patient visit. Only ONE active session can exist
 * per device at any time (a device monitors one patient at a time).
 *
 * Session lifecycle:
 *   1. START: Nurse assigns device to patient visit → session created, device → MONITORING
 *   2. ACTIVE: Device streams vitals, linked to this visit
 *   3. END: Nurse disconnects device OR patient is discharged → session closed, device → ONLINE
 *
 * Historical sessions provide an audit trail of which devices monitored which patients.
 */
@Entity
@Table(name = "device_sessions", indexes = {
        @Index(name = "idx_device_session_device", columnList = "device_id"),
        @Index(name = "idx_device_session_visit", columnList = "visit_id"),
        @Index(name = "idx_device_session_started", columnList = "started_at"),
        @Index(name = "idx_device_session_ended", columnList = "ended_at"),
        @Index(name = "idx_device_session_active_flag", columnList = "session_active"),
        @Index(name = "idx_device_session_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private IoTDevice device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    /** When monitoring started */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** When monitoring ended (null if still active) */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** Whether this session is currently active */
    @Column(name = "session_active", nullable = false)
    @Builder.Default
    private boolean sessionActive = true;

    /** Name of the clinician who initiated the monitoring session */
    @Column(name = "started_by_name", length = 255)
    private String startedByName;

    /** Name of the clinician who ended the monitoring session */
    @Column(name = "ended_by_name", length = 255)
    private String endedByName;

    /** Reason for ending the session */
    @Column(name = "end_reason", length = 255)
    private String endReason;

    /** Total number of vital readings received during this session */
    @Column(name = "total_readings", nullable = false)
    @Builder.Default
    private long totalReadings = 0;

    /** Number of readings that failed validation */
    @Column(name = "rejected_readings", nullable = false)
    @Builder.Default
    private long rejectedReadings = 0;

    /** Number of alerts generated during this session */
    @Column(name = "alerts_generated", nullable = false)
    @Builder.Default
    private int alertsGenerated = 0;

    /** Number of auto-retriages triggered during this session */
    @Column(name = "retriages_triggered", nullable = false)
    @Builder.Default
    private int retriagesTriggered = 0;

    /**
     * Current trend classification derived from recent VitalStream readings.
     * Updated by ContinuousMonitoringEngine on each ingest, with hysteresis —
     * two consecutive classifications must agree before this field changes.
     * Null/UNKNOWN until enough readings accumulate.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "trend_status", length = 16)
    @Builder.Default
    private TrendStatus trendStatus = TrendStatus.UNKNOWN;

    /** Timestamp of the last trend recalculation (for audit / freshness checks). */
    @Column(name = "trend_updated_at")
    private Instant trendUpdatedAt;

    /**
     * Last proposed classification held as a "candidate" pending confirmation.
     * If the next tick agrees with this value, {@link #trendStatus} is updated.
     * Internal hysteresis state — not exposed in DTOs.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "trend_candidate", length = 16)
    private TrendStatus trendCandidate;

    public void incrementReadings() {
        this.totalReadings++;
    }

    public void incrementRejected() {
        this.rejectedReadings++;
    }

    public void incrementAlerts() {
        this.alertsGenerated++;
    }

    public void incrementRetriages() {
        this.retriagesTriggered++;
    }

    public void endSession(String endedByName, String reason) {
        this.sessionActive = false;
        this.endedAt = Instant.now();
        this.endedByName = endedByName;
        this.endReason = reason;
    }
}
