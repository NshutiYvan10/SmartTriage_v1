package com.smartTriage.smartTriage_server.module.visit.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.ArrivalMode;
import com.smartTriage.smartTriage_server.common.enums.DispositionType;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Visit entity — represents a single ED encounter.
 * A patient can have many visits over time.
 * Each visit is an independent clinical event with its own triage, vitals, and disposition.
 *
 * The visit is the anchor record for the entire ED workflow:
 * Registration → Triage → Monitoring → Assessment → Disposition
 */
@Entity
@Table(name = "visits", indexes = {
        @Index(name = "idx_visit_patient", columnList = "patient_id"),
        @Index(name = "idx_visit_hospital", columnList = "hospital_id"),
        @Index(name = "idx_visit_status", columnList = "status"),
        @Index(name = "idx_visit_triage_category", columnList = "current_triage_category"),
        @Index(name = "idx_visit_arrival", columnList = "arrival_time"),
        @Index(name = "idx_visit_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Visit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(name = "visit_number", nullable = false, unique = true, length = 30)
    private String visitNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "arrival_mode", length = 20)
    private ArrivalMode arrivalMode;

    @Column(name = "arrival_time", nullable = false)
    private Instant arrivalTime;

    @Column(name = "chief_complaint", columnDefinition = "TEXT")
    private String chiefComplaint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private VisitStatus status = VisitStatus.REGISTERED;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_triage_category", length = 10)
    private TriageCategory currentTriageCategory;

    @Column(name = "current_tews_score")
    private Integer currentTewsScore;

    @Column(name = "triage_time")
    private Instant triageTime;

    @Column(name = "assessment_start_time")
    private Instant assessmentStartTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition_type", length = 30)
    private DispositionType dispositionType;

    @Column(name = "disposition_time")
    private Instant dispositionTime;

    @Column(name = "disposition_notes", columnDefinition = "TEXT")
    private String dispositionNotes;

    @Column(name = "referring_facility")
    private String referringFacility;

    @Column(name = "is_pediatric", nullable = false)
    @Builder.Default
    private boolean isPediatric = false;

    @Column(name = "retriage_count", nullable = false)
    @Builder.Default
    private int retriageCount = 0;

    /**
     * The bed this visit is currently placed in (null = not yet placed,
     * or patient is ambulatory in General / Triage). Mirrors
     * Bed.currentVisit and is kept in sync by BedService. A partial
     * unique index (uk_visit_one_bed) prevents double-placement.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_bed_id")
    private Bed currentBed;

    // ═══════════════════════════════════════════════════════════════
    // Direct Resus Admission flags (V28)
    // ═══════════════════════════════════════════════════════════════

    /**
     * TRUE when the visit was admitted to RESUS but no bed was
     * available. Patient is on a stretcher in the resus area; the
     * charge nurse sees a transfer prompt to free up a bed by
     * moving out a stabilised occupant. Cleared as soon as the
     * patient is placed in an actual bed.
     */
    @Column(name = "pending_resus_overflow", nullable = false)
    @Builder.Default
    private boolean pendingResusOverflow = false;

    /**
     * TRUE when this visit was created from an ambulance call-ahead
     * (radio handover, ETA known) before the patient physically
     * arrived. The bed is reserved, the team is alerted, but the
     * door clock has not started.
     */
    @Column(name = "ambulance_pre_arrival", nullable = false)
    @Builder.Default
    private boolean ambulancePreArrival = false;

    /**
     * For ambulance pre-arrival visits: when the nurse confirms the
     * patient has actually walked through the door. Only set if
     * ambulancePreArrival was true. NULL until confirmation.
     */
    @Column(name = "arrival_confirmed_at")
    private Instant arrivalConfirmedAt;
}
