package com.smartTriage.smartTriage_server.module.sepsis.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * SepsisScreening entity — records a sepsis screening event for a patient visit.
 *
 * Captures both qSOFA and SIRS scoring criteria, the resulting sepsis status,
 * and tracks the 1-hour sepsis bundle compliance. Based on Rwanda MoH sepsis
 * management guidelines and the Surviving Sepsis Campaign.
 *
 * A visit may have multiple screening records as the patient's condition evolves.
 */
@Entity
@Table(name = "sepsis_screenings", indexes = {
        @Index(name = "idx_sepsis_visit", columnList = "visit_id"),
        @Index(name = "idx_sepsis_status", columnList = "sepsis_status"),
        @Index(name = "idx_sepsis_screened_at", columnList = "screened_at"),
        @Index(name = "idx_sepsis_active", columnList = "is_active"),
        @Index(name = "idx_sepsis_bundle_started", columnList = "bundle_started_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SepsisScreening extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "screened_at", nullable = false)
    private Instant screenedAt;

    @Column(name = "screened_by_name")
    private String screenedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "sepsis_status", nullable = false, length = 20)
    private SepsisStatus sepsisStatus;

    // ====================================================================
    // qSOFA SCORING (Quick Sequential Organ Failure Assessment)
    // ====================================================================

    @Column(name = "qsofa_score", nullable = false)
    @Builder.Default
    private int qsofaScore = 0;

    @Column(name = "altered_mentation", nullable = false)
    @Builder.Default
    private boolean alteredMentation = false;

    @Column(name = "respiratory_rate_high", nullable = false)
    @Builder.Default
    private boolean respiratoryRateHigh = false;

    @Column(name = "systolic_bp_low", nullable = false)
    @Builder.Default
    private boolean systolicBpLow = false;

    // ====================================================================
    // SIRS CRITERIA (Systemic Inflammatory Response Syndrome)
    // ====================================================================

    @Column(name = "sirs_score", nullable = false)
    @Builder.Default
    private int sirsScore = 0;

    /** Temperature > 38°C or < 36°C */
    @Column(name = "temperature_criteria_met", nullable = false)
    @Builder.Default
    private boolean temperatureCriteriaMet = false;

    /** Heart rate > 90 bpm */
    @Column(name = "heart_rate_criteria_met", nullable = false)
    @Builder.Default
    private boolean heartRateCriteriaMet = false;

    /** Respiratory rate > 20 breaths/min */
    @Column(name = "respiratory_rate_criteria_met", nullable = false)
    @Builder.Default
    private boolean respiratoryRateCriteriaMet = false;

    /** WBC > 12,000 or < 4,000 or > 10% bands */
    @Column(name = "wbc_criteria_met", nullable = false)
    @Builder.Default
    private boolean wbcCriteriaMet = false;

    // ====================================================================
    // CLINICAL CONTEXT
    // ====================================================================

    @Column(name = "suspected_infection_source", columnDefinition = "TEXT")
    private String suspectedInfectionSource;

    @Column(name = "lactate_level")
    private Double lactateLevel;

    // ====================================================================
    // 1-HOUR SEPSIS BUNDLE TRACKING
    // ====================================================================

    @Column(name = "bundle_started_at")
    private Instant bundleStartedAt;

    @Column(name = "bundle_completed_at")
    private Instant bundleCompletedAt;

    @Column(name = "blood_culture_obtained", nullable = false)
    @Builder.Default
    private boolean bloodCultureObtained = false;

    @Column(name = "broad_spectrum_antibiotics", nullable = false)
    @Builder.Default
    private boolean broadSpectrumAntibiotics = false;

    @Column(name = "iv_crystalloid_bolus", nullable = false)
    @Builder.Default
    private boolean ivCrystalloidBolus = false;

    @Column(name = "lactate_measured", nullable = false)
    @Builder.Default
    private boolean lactateMeasured = false;

    @Column(name = "vasopressors_if_needed", nullable = false)
    @Builder.Default
    private boolean vasopressorsIfNeeded = false;

    @Column(name = "repeat_lactate_if_elevated", nullable = false)
    @Builder.Default
    private boolean repeatLactateIfElevated = false;

    // ====================================================================
    // NOTES
    // ====================================================================

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
