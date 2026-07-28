package com.smartTriage.smartTriage_server.module.iot.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.MonitoringEventType;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * MonitoringEvent — one row per monitoring-engine TRANSITION (V119).
 *
 * The chronological record of what the continuous monitoring engine saw
 * and did for a visit: pattern detected / changed / cleared, confirmed
 * trend changes, auto-retriages, and session lifecycle. Written even when
 * the corresponding clinical alert is dedup-suppressed — alerts page
 * humans, this log records history (the handover question "what happened,
 * in what order?").
 *
 * Append-only by convention: rows are created by the engine and session
 * lifecycle only, never updated.
 */
@Entity
@Table(name = "monitoring_events", indexes = {
        @Index(name = "idx_monitoring_events_visit_time", columnList = "visit_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitoringEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    /** Session the event belongs to — null only for visit-level events. */
    @Column(name = "session_id")
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private MonitoringEventType eventType;

    /** Short human-readable headline — e.g. "SEPSIS PATTERN detected". */
    @Column(name = "label", nullable = false, length = 160)
    private String label;

    /** Full finding text — e.g. the SIRS constellation description. */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    // ── Vitals context at the moment of the event (nullable) ──

    @Column(name = "heart_rate")
    private Integer heartRate;

    @Column(name = "spo2")
    private Integer spo2;

    @Column(name = "respiratory_rate")
    private Integer respiratoryRate;

    @Column(name = "systolic_bp")
    private Integer systolicBp;

    @Column(name = "temperature")
    private Double temperature;
}
