package com.smartTriage.smartTriage_server.module.safety.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.IncidentSeverity;
import com.smartTriage.smartTriage_server.common.enums.IncidentStatus;
import com.smartTriage.smartTriage_server.common.enums.IncidentType;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * SafetyIncident — represents a patient safety incident report.
 *
 * Aligned with Rwanda's patient safety and quality improvement frameworks.
 * Supports the full incident lifecycle: reporting -> investigation -> root cause analysis
 * -> corrective action -> closure with lessons learned.
 *
 * Supports anonymous reporting to encourage a culture of safety.
 */
@Entity
@Table(name = "safety_incidents", indexes = {
        @Index(name = "idx_incident_hospital", columnList = "hospital_id"),
        @Index(name = "idx_incident_visit", columnList = "visit_id"),
        @Index(name = "idx_incident_number", columnList = "incident_number", unique = true),
        @Index(name = "idx_incident_type", columnList = "incident_type"),
        @Index(name = "idx_incident_severity", columnList = "severity"),
        @Index(name = "idx_incident_status", columnList = "status"),
        @Index(name = "idx_incident_datetime", columnList = "incident_date_time"),
        @Index(name = "idx_incident_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SafetyIncident extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @Column(name = "incident_number", nullable = false, unique = true, length = 20)
    private String incidentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false, length = 35)
    private IncidentType incidentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private IncidentSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 35)
    @Builder.Default
    private IncidentStatus status = IncidentStatus.REPORTED;

    // ====================================================================
    // INCIDENT DETAILS
    // ====================================================================

    @Column(name = "incident_date_time", nullable = false)
    private Instant incidentDateTime;

    @Column(name = "location_in_hospital")
    private String locationInHospital;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "contributing_factors", columnDefinition = "TEXT")
    private String contributingFactors;

    @Column(name = "immediate_actions", columnDefinition = "TEXT")
    private String immediateActions;

    // ====================================================================
    // PEOPLE INVOLVED
    // ====================================================================

    @Column(name = "reported_by_name", nullable = false)
    private String reportedByName;

    @Column(name = "reported_by_role")
    private String reportedByRole;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Column(name = "involved_staff_names", columnDefinition = "TEXT")
    private String involvedStaffNames;

    @Column(name = "patient_harmed")
    private Boolean patientHarmed;

    // ====================================================================
    // INVESTIGATION
    // ====================================================================

    @Column(name = "investigator_name")
    private String investigatorName;

    @Column(name = "investigation_started_at")
    private Instant investigationStartedAt;

    @Column(name = "root_cause_analysis", columnDefinition = "TEXT")
    private String rootCauseAnalysis;

    @Column(name = "root_cause_category")
    private String rootCauseCategory;

    @Column(name = "investigation_completed_at")
    private Instant investigationCompletedAt;

    // ====================================================================
    // CORRECTIVE ACTION
    // ====================================================================

    @Column(name = "corrective_action", columnDefinition = "TEXT")
    private String correctiveAction;

    @Column(name = "corrective_action_owner")
    private String correctiveActionOwner;

    @Column(name = "corrective_action_deadline")
    private Instant correctiveActionDeadline;

    @Column(name = "corrective_action_completed_at")
    private Instant correctiveActionCompletedAt;

    @Column(name = "preventive_measures", columnDefinition = "TEXT")
    private String preventiveMeasures;

    // ====================================================================
    // CLOSURE
    // ====================================================================

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by_name")
    private String closedByName;

    @Column(name = "lessons_learned", columnDefinition = "TEXT")
    private String lessonsLearned;

    // ====================================================================
    // ANONYMOUS REPORTING
    // ====================================================================

    @Column(name = "is_anonymous", nullable = false)
    @Builder.Default
    private boolean isAnonymous = false;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
