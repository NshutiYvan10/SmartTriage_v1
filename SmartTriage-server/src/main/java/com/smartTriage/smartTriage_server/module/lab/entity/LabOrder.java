package com.smartTriage.smartTriage_server.module.lab.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * LabOrder — enhanced lab workflow entity that links to the existing Investigation
 * entity and adds STAT lab tracking, critical value detection, and turnaround
 * time monitoring.
 *
 * Lifecycle:
 *   ORDER → SPECIMEN_COLLECTED → RECEIVED_BY_LAB → PROCESSING → RESULTED / CANCELLED
 *
 * Order number format: LAB-YYYYMMDD-XXXXX (auto-generated)
 */
@Entity
@Table(name = "lab_orders", indexes = {
        @Index(name = "idx_lab_order_visit", columnList = "visit_id"),
        @Index(name = "idx_lab_order_number", columnList = "order_number", unique = true),
        @Index(name = "idx_lab_order_priority", columnList = "priority"),
        @Index(name = "idx_lab_order_critical", columnList = "is_critical"),
        @Index(name = "idx_lab_order_ordered_at", columnList = "ordered_at"),
        @Index(name = "idx_lab_order_resulted_at", columnList = "resulted_at"),
        @Index(name = "idx_lab_order_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabOrder extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    /** Link to existing Investigation entity — nullable for standalone lab orders */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "investigation_id")
    private Investigation investigation;

    /** Auto-generated order number: LAB-YYYYMMDD-XXXXX */
    @Column(name = "order_number", nullable = false, unique = true, length = 30)
    private String orderNumber;

    @Column(name = "test_name", nullable = false, length = 255)
    private String testName;

    /** Lab-specific test code */
    @Column(name = "test_code", length = 50)
    private String testCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 15)
    private LabPriority priority;

    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt;

    @Column(name = "ordered_by_name", length = 255)
    private String orderedByName;

    /** Specimen type — e.g., "blood", "urine", "CSF", "sputum" */
    @Column(name = "specimen_type", length = 50)
    private String specimenType;

    @Column(name = "specimen_collected_at")
    private Instant specimenCollectedAt;

    @Column(name = "specimen_collected_by_name", length = 255)
    private String specimenCollectedByName;

    @Column(name = "received_by_lab_at")
    private Instant receivedByLabAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "resulted_at")
    private Instant resultedAt;

    /** The result value as text */
    @Column(name = "result_value", columnDefinition = "TEXT")
    private String resultValue;

    /** Unit of measurement — e.g., "mmol/L", "g/dL", "cells/uL" */
    @Column(name = "result_unit", length = 50)
    private String resultUnit;

    /** Parsed numeric value for comparison against reference ranges */
    @Column(name = "result_numeric")
    private Double resultNumeric;

    @Column(name = "reference_range_min")
    private Double referenceRangeMin;

    @Column(name = "reference_range_max")
    private Double referenceRangeMax;

    @Column(name = "is_abnormal", nullable = false)
    @Builder.Default
    private boolean isAbnormal = false;

    @Column(name = "is_critical", nullable = false)
    @Builder.Default
    private boolean isCritical = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "critical_value_type", length = 30)
    private CriticalValueType criticalValueType;

    @Column(name = "critical_value_notified_at")
    private Instant criticalValueNotifiedAt;

    /** Name of clinician notified about the critical value */
    @Column(name = "critical_value_notified_to", length = 255)
    private String criticalValueNotifiedTo;

    @Column(name = "critical_value_acknowledged_at")
    private Instant criticalValueAcknowledgedAt;

    /** Calculated turnaround time in minutes: orderedAt to resultedAt */
    @Column(name = "turnaround_minutes")
    private Integer turnaroundMinutes;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by_name", length = 255)
    private String cancelledByName;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
}
