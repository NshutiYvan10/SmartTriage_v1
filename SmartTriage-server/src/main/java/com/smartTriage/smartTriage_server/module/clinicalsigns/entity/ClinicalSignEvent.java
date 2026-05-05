package com.smartTriage.smartTriage_server.module.clinicalsigns.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * ClinicalSignEvent — a single recorded observation of a clinical sign
 * at a point in time.
 *
 * Event-log model: instead of overwriting the patient's status, every
 * change is a row. The "current state" of a sign is the latest event
 * for that visit + sign code. Trajectory is the sequence of events
 * over time.
 *
 * Bootstrapping: when triage is performed, one event per positive
 * triage flag is auto-recorded with isBaseline=true. After that,
 * doctors and nurses record updates as patients are reassessed.
 *
 * Why not snapshot 54 columns per row: most signs change zero or
 * one time during a visit. Snapshotting all 54 every observation
 * generates massive empty-column volume. Events only carry the
 * data that actually changed.
 */
@Entity
@Table(name = "clinical_sign_events", indexes = {
        @Index(name = "idx_clinical_sign_visit", columnList = "visit_id"),
        @Index(name = "idx_clinical_sign_patient", columnList = "patient_id"),
        @Index(name = "idx_clinical_sign_visit_code_time", columnList = "visit_id, sign_code, recorded_at"),
        @Index(name = "idx_clinical_sign_recorded_at", columnList = "recorded_at"),
        @Index(name = "idx_clinical_sign_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClinicalSignEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    /** Denormalized for fast cross-visit queries on the same patient. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /**
     * Stable string code identifying the sign — e.g.
     * "EMERGENCY_CONVULSIONS", "MSAT_VU_CHEST_PAIN", "PEDS_EMERGENCY_CENTRAL_CYANOSIS".
     * The catalog of valid codes lives in ClinicalSignDefinitions on both
     * backend and frontend; we deliberately keep this as a flat string here
     * (no FK) to avoid coupling the audit log to a reference table that may
     * evolve.
     */
    @Column(name = "sign_code", nullable = false, length = 60)
    private String signCode;

    /** Top-level category (EMERGENCY / PEDIATRIC_EMERGENCY / MSAT_VU / ...). */
    @Enumerated(EnumType.STRING)
    @Column(name = "sign_category", nullable = false, length = 30)
    private ClinicalSignCategory signCategory;

    /** State at this event time. See ClinicalSignStatus for semantics. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private ClinicalSignStatus status;

    /**
     * Optional numeric value carried with the event — used today only for
     * glucose readings on convulsions / coma / DKA discriminators. Other
     * physiological numerics (HR, SpO2, GCS, pain score) live in
     * vital_signs and should not be duplicated here.
     */
    @Column(name = "numeric_value")
    private Double numericValue;

    /** Optional clinician annotation — what changed, what was done. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** When the observation was made (clinically — not necessarily insert time). */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_id")
    private User recordedBy;

    /** Display-name fallback when the User reference is unavailable (e.g. backfill). */
    @Column(name = "recorded_by_name", length = 200)
    private String recordedByName;

    /**
     * True for the auto-generated entries that bootstrap the timeline from
     * the triage record. Distinguishing baseline events lets the UI render
     * them differently ("Baseline at triage" vs "Update at 14:30") and lets
     * the re-triage engine distinguish "was already positive on arrival"
     * from "newly emerged".
     */
    @Column(name = "is_baseline", nullable = false)
    @Builder.Default
    private boolean isBaseline = false;
}
