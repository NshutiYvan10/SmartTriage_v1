package com.smartTriage.smartTriage_server.module.medication.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.DoseKind;
import com.smartTriage.smartTriage_server.common.enums.DoseStatus;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * MedicationDose — one administration EVENT against a medication order
 * (Medication Management module, V67).
 *
 * <p>The order ({@link MedicationAdministration}) says WHAT was
 * prescribed; dose rows say what actually HAPPENED, when, and by whom:
 * <ul>
 *   <li>ONE_TIME / SCHEDULED — one row per dose. SCHEDULED orders get
 *       their next DUE row created the moment the previous dose is
 *       given, so there is always exactly one open dose per live
 *       recurring order.</li>
 *   <li>PRN — a row is created at the moment the nurse administers
 *       (no pre-created DUE rows), carrying the indication and the
 *       vitals-gate evaluation snapshot.</li>
 *   <li>CONTINUOUS — an event log: INFUSION_START (rate),
 *       INFUSION_RATE_CHANGE per adjustment, INFUSION_STOP.</li>
 * </ul>
 *
 * <p>Together the rows are the per-patient medication audit trail the
 * handover report renders: every dose given/missed/refused with actor,
 * time, witness, override justification, and reason.
 */
@Entity
@Table(name = "medication_doses", indexes = {
        @Index(name = "idx_med_dose_medication", columnList = "medication_id"),
        @Index(name = "idx_med_dose_visit", columnList = "visit_id"),
        @Index(name = "idx_med_dose_status_due", columnList = "status, due_at"),
        @Index(name = "idx_med_dose_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationDose extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medication_id", nullable = false)
    private MedicationAdministration medication;

    /** Denormalised for fast zone-board and per-visit audit queries. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private DoseKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private DoseStatus status = DoseStatus.DUE;

    /** Dose #N of the order (1-based) — drives "dose 3 of 6" displays. */
    @Column(name = "sequence_number")
    private Integer sequenceNumber;

    /** When this dose should be given. NULL for PRN / infusion events. */
    @Column(name = "due_at")
    private Instant dueAt;

    // ── Administration record ──

    @Column(name = "given_at")
    private Instant givenAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "given_by_id")
    private User givenBy;

    @Column(name = "given_by_name", length = 255)
    private String givenByName;

    /** Second-clinician witness (blood products / high-alert drugs). */
    @Column(name = "witness_name", length = 255)
    private String witnessName;

    /** Verified administered dose — nurse confirms what they are giving. */
    @Column(name = "dose_value", precision = 10, scale = 3)
    private BigDecimal doseValue;

    @Column(name = "dose_unit", length = 20)
    private String doseUnit;

    // ── Infusion events ──

    @Column(name = "rate_value")
    private Double rateValue;

    @Column(name = "rate_unit", length = 20)
    private String rateUnit;

    /** PRN: the condition that triggered this dose ("pain 6/10"). */
    @Column(name = "prn_reason", length = 255)
    private String prnReason;

    /** Vitals-gate snapshot at administration ("SBP 102 ≥ 100 — passed"). */
    @Column(name = "gate_evaluation", length = 500)
    private String gateEvaluation;

    // ── Override trail ──

    @Column(name = "is_override", nullable = false)
    @Builder.Default
    private boolean isOverride = false;

    @Column(name = "override_justification", columnDefinition = "TEXT")
    private String overrideJustification;

    /** Append-only delay / refuse / miss / cancel reason trail. */
    @Column(name = "status_reason", columnDefinition = "TEXT")
    private String statusReason;

    @Column(name = "delay_count", nullable = false)
    @Builder.Default
    private int delayCount = 0;

    // ── Scheduler bookkeeping (no alert spam on every tick) ──

    @Column(name = "overdue_notified_at")
    private Instant overdueNotifiedAt;

    @Column(name = "missed_escalated_at")
    private Instant missedEscalatedAt;

    /** Append a line to the status-reason trail (audit, never overwrite). */
    public void appendStatusReason(String line) {
        if (line == null || line.isBlank()) return;
        this.statusReason = this.statusReason == null || this.statusReason.isBlank()
                ? line
                : this.statusReason + " | " + line;
    }
}
