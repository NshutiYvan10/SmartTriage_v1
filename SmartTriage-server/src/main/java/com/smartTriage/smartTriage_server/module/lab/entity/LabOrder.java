package com.smartTriage.smartTriage_server.module.lab.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.CriticalContactMethod;
import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import com.smartTriage.smartTriage_server.common.enums.LabOrderStatus;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.common.enums.SpecimenRejectionReason;
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

    /**
     * Authoritative workflow state. Backfilled from timestamps in V48
     * for existing rows; new rows must set this explicitly.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private LabOrderStatus status = LabOrderStatus.ORDERED;

    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt;

    @Column(name = "ordered_by_name", length = 255)
    private String orderedByName;

    /** Why the test was ordered (rule out sepsis, AKI workup, etc.). */
    @Column(name = "clinical_indication", length = 500)
    private String clinicalIndication;

    /** Specimen type — e.g., "blood", "urine", "CSF", "sputum" */
    @Column(name = "specimen_type", length = 50)
    private String specimenType;

    @Column(name = "specimen_collected_at")
    private Instant specimenCollectedAt;

    @Column(name = "specimen_collected_by_name", length = 255)
    private String specimenCollectedByName;

    /** Lab acknowledged it has SEEN the order (distinct from specimen receipt) — lets a
     *  doctor see the lab has picked it up. Does not change workflow status (V82). */
    @Column(name = "acknowledged_by_lab_at")
    private Instant acknowledgedByLabAt;

    @Column(name = "acknowledged_by_lab_name", length = 255)
    private String acknowledgedByLabName;

    @Column(name = "received_by_lab_at")
    private Instant receivedByLabAt;

    /** Lab-side accession (barcode written on the tube on receipt). */
    @Column(name = "accession_number", length = 40)
    private String accessionNumber;

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

    // ── Specimen rejection (closes the haemolysed/clotted/mislabelled loop)

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejected_by_name", length = 255)
    private String rejectedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 50)
    private SpecimenRejectionReason rejectionReason;

    @Column(name = "rejection_notes", length = 1000)
    private String rejectionNotes;

    // ── Two-step verification (Phase 2: senior tech gates high-risk results)

    @Column(name = "entered_by_name", length = 255)
    private String enteredByName;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verified_by_name", length = 255)
    private String verifiedByName;

    /** True if this result went through the senior-verification gate. */
    @Column(name = "verification_required", nullable = false)
    @Builder.Default
    private boolean verificationRequired = false;

    /** When AWAITING_VERIFICATION auto-releases if no senior signs off. */
    @Column(name = "verification_timeout_at")
    private Instant verificationTimeoutAt;

    /** Set true when the timeout fires before a senior verifies. */
    @Column(name = "verification_auto_released", nullable = false)
    @Builder.Default
    private boolean verificationAutoReleased = false;

    /** Junior tech emergency override (released without verification). */
    @Column(name = "verification_override", nullable = false)
    @Builder.Default
    private boolean verificationOverride = false;

    @Column(name = "verification_override_reason", length = 500)
    private String verificationOverrideReason;

    @Column(name = "verification_override_by_name", length = 255)
    private String verificationOverrideByName;

    @Column(name = "verification_override_at")
    private Instant verificationOverrideAt;

    /** Number of times a senior bounced this back to the junior. */
    @Column(name = "verification_rejection_count", nullable = false)
    @Builder.Default
    private int verificationRejectionCount = 0;

    @Column(name = "verification_rejection_reason", length = 500)
    private String verificationRejectionReason;

    @Column(name = "verification_rejected_by_name", length = 255)
    private String verificationRejectedByName;

    @Column(name = "verification_rejected_at")
    private Instant verificationRejectedAt;

    // ── Critical-value read-back attestation (JCI NPSG.02.03.01)

    @Column(name = "critical_readback_text", length = 1000)
    private String criticalReadbackText;

    @Enumerated(EnumType.STRING)
    @Column(name = "critical_contact_method", length = 20)
    private CriticalContactMethod criticalContactMethod;
}
