package com.smartTriage.smartTriage_server.module.isolation.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.InfectionRiskLevel;
import com.smartTriage.smartTriage_server.common.enums.IsolationType;
import com.smartTriage.smartTriage_server.common.enums.NotifiableDisease;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * InfectionScreening — records infection screening results, isolation requirements, and PPE mandates.
 *
 * Critical for Rwanda context:
 * - TB: Rwanda is a high-burden country
 * - Ebola/Marburg: Rwanda experienced a Marburg outbreak in 2023
 * - Notifiable diseases require reporting to Rwanda Biomedical Centre (RBC)
 *   within 24 hours per IDSR protocol
 */
@Entity
@Table(name = "infection_screenings", indexes = {
        @Index(name = "idx_inf_visit", columnList = "visit_id"),
        @Index(name = "idx_inf_risk_level", columnList = "risk_level"),
        @Index(name = "idx_inf_isolation_type", columnList = "isolation_type"),
        @Index(name = "idx_inf_notifiable", columnList = "notifiable_disease"),
        @Index(name = "idx_inf_screened_at", columnList = "screened_at"),
        @Index(name = "idx_inf_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfectionScreening extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "screened_at")
    private Instant screenedAt;

    @Column(name = "screened_by_name")
    private String screenedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 20)
    private InfectionRiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "isolation_type", length = 20)
    private IsolationType isolationType;

    @Column(name = "suspected_condition")
    private String suspectedCondition;

    @Enumerated(EnumType.STRING)
    @Column(name = "notifiable_disease", length = 30)
    private NotifiableDisease notifiableDisease;

    // ====================================================================
    // SCREENING CRITERIA
    // ====================================================================

    @Column(name = "has_fever", nullable = false)
    @Builder.Default
    private boolean hasFever = false;

    @Column(name = "has_cough", nullable = false)
    @Builder.Default
    private boolean hasCough = false;

    /** TB screening: cough duration in weeks. > 2 weeks is significant. */
    @Column(name = "has_cough_duration_weeks")
    private Integer hasCoughDurationWeeks;

    @Column(name = "has_night_sweats", nullable = false)
    @Builder.Default
    private boolean hasNightSweats = false;

    @Column(name = "has_weight_loss", nullable = false)
    @Builder.Default
    private boolean hasWeightLoss = false;

    @Column(name = "has_rash", nullable = false)
    @Builder.Default
    private boolean hasRash = false;

    @Column(name = "has_diarrhea", nullable = false)
    @Builder.Default
    private boolean hasDiarrhea = false;

    @Column(name = "has_recent_travel", nullable = false)
    @Builder.Default
    private boolean hasRecentTravel = false;

    @Column(name = "recent_travel_location")
    private String recentTravelLocation;

    @Column(name = "has_contact_with_infectious", nullable = false)
    @Builder.Default
    private boolean hasContactWithInfectious = false;

    @Column(name = "contact_details", columnDefinition = "TEXT")
    private String contactDetails;

    /** Ebola/Marburg screening — bleeding symptoms */
    @Column(name = "has_bleeding_symptoms", nullable = false)
    @Builder.Default
    private boolean hasBleedingSymptoms = false;

    @Column(name = "is_healthcare_worker", nullable = false)
    @Builder.Default
    private boolean isHealthcareWorker = false;

    // ====================================================================
    // PPE REQUIREMENTS
    // ====================================================================

    @Column(name = "requires_n95", nullable = false)
    @Builder.Default
    private boolean requiresN95 = false;

    @Column(name = "requires_gown", nullable = false)
    @Builder.Default
    private boolean requiresGown = false;

    @Column(name = "requires_gloves", nullable = false)
    @Builder.Default
    private boolean requiresGloves = false;

    @Column(name = "requires_face_shield", nullable = false)
    @Builder.Default
    private boolean requiresFaceShield = false;

    @Column(name = "requires_apron", nullable = false)
    @Builder.Default
    private boolean requiresApron = false;

    @Column(name = "requires_boot_covers", nullable = false)
    @Builder.Default
    private boolean requiresBootCovers = false;

    // ====================================================================
    // ISOLATION ACTIONS
    // ====================================================================

    @Column(name = "isolation_room_assigned")
    private String isolationRoomAssigned;

    @Column(name = "isolation_started_at")
    private Instant isolationStartedAt;

    @Column(name = "isolation_ended_at")
    private Instant isolationEndedAt;

    /** When Rwanda RBC/MoH was notified */
    @Column(name = "public_health_notified_at")
    private Instant publicHealthNotifiedAt;

    @Column(name = "public_health_reference_number")
    private String publicHealthReferenceNumber;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
