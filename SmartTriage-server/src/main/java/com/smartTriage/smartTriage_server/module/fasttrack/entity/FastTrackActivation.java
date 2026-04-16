package com.smartTriage.smartTriage_server.module.fasttrack.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.FastTrackStatus;
import com.smartTriage.smartTriage_server.common.enums.FastTrackType;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * FastTrackActivation — records activation and progression of a stroke or MI fast-track protocol.
 *
 * Time-critical conditions where every minute counts:
 * - Stroke: thrombolysis window is 4.5 hours from symptom onset
 * - STEMI: door-to-balloon target < 90 minutes
 * - Rwanda context: door-to-ECG < 10 minutes, door-to-CT < 25 minutes (adapted for available resources)
 */
@Entity
@Table(name = "fast_track_activations", indexes = {
        @Index(name = "idx_ft_visit", columnList = "visit_id"),
        @Index(name = "idx_ft_type", columnList = "fast_track_type"),
        @Index(name = "idx_ft_status", columnList = "status"),
        @Index(name = "idx_ft_activated_at", columnList = "activated_at"),
        @Index(name = "idx_ft_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FastTrackActivation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Enumerated(EnumType.STRING)
    @Column(name = "fast_track_type", nullable = false, length = 30)
    private FastTrackType fastTrackType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private FastTrackStatus status = FastTrackStatus.ACTIVATED;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "activated_by_name")
    private String activatedByName;

    // ====================================================================
    // STROKE-SPECIFIC FIELDS
    // ====================================================================

    /** Critical for thrombolysis window calculation (4.5 hours) */
    @Column(name = "symptom_onset_time")
    private Instant symptomOnsetTime;

    /** Balance, Eyes, Face, Arm, Speech, Time screening result */
    @Column(name = "be_fast_score")
    private String beFastScore;

    /** NIH Stroke Scale score (0-42) */
    @Column(name = "nihss_score")
    private Integer nihssScore;

    @Column(name = "ct_ordered_at")
    private Instant ctOrderedAt;

    @Column(name = "ct_completed_at")
    private Instant ctCompletedAt;

    @Column(name = "ct_result", columnDefinition = "TEXT")
    private String ctResult;

    /** Hemorrhagic vs ischemic stroke — determines treatment pathway */
    @Column(name = "is_hemorrhagic")
    private Boolean isHemorrhagic;

    @Column(name = "thrombolysis_eligible")
    private Boolean thrombolysisEligible;

    @Column(name = "thrombolysis_started_at")
    private Instant thrombolysisStartedAt;

    /** Calculated: arrival time to CT completion in minutes */
    @Column(name = "door_to_ct_minutes")
    private Integer doorToCtMinutes;

    // ====================================================================
    // MI-SPECIFIC FIELDS
    // ====================================================================

    @Column(name = "chest_pain_onset_time")
    private Instant chestPainOnsetTime;

    @Column(name = "ecg_ordered_at")
    private Instant ecgOrderedAt;

    @Column(name = "ecg_completed_at")
    private Instant ecgCompletedAt;

    @Column(name = "ecg_result", columnDefinition = "TEXT")
    private String ecgResult;

    @Column(name = "st_elevation")
    private Boolean stElevation;

    @Column(name = "troponin_ordered")
    private Boolean troponinOrdered;

    @Column(name = "troponin_result")
    private Double troponinResult;

    @Column(name = "troponin_resulted_at")
    private Instant troponinResultedAt;

    @Column(name = "aspirin_given")
    private Boolean aspirinGiven;

    @Column(name = "aspirin_given_at")
    private Instant aspirinGivenAt;

    @Column(name = "anticoagulant_given")
    private Boolean anticoagulantGiven;

    @Column(name = "referred_for_pci")
    private Boolean referredForPci;

    @Column(name = "referred_for_pci_at")
    private Instant referredForPciAt;

    /** Calculated: arrival time to ECG completion in minutes */
    @Column(name = "door_to_ecg_minutes")
    private Integer doorToEcgMinutes;

    /** Calculated: arrival time to thrombolysis/intervention start in minutes */
    @Column(name = "door_to_needle_minutes")
    private Integer doorToNeedleMinutes;

    // ====================================================================
    // OUTCOME
    // ====================================================================

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "outcome", columnDefinition = "TEXT")
    private String outcome;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
