package com.smartTriage.smartTriage_server.module.safety.dto;

import com.smartTriage.smartTriage_server.common.enums.IncidentSeverity;
import com.smartTriage.smartTriage_server.common.enums.IncidentStatus;
import com.smartTriage.smartTriage_server.common.enums.IncidentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for safety incident data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SafetyIncidentResponse {

    private UUID id;
    private UUID hospitalId;
    private String hospitalName;
    private UUID visitId;
    private String visitNumber;

    private String incidentNumber;
    private IncidentType incidentType;
    private IncidentSeverity severity;
    private IncidentStatus status;

    // Incident details
    private Instant incidentDateTime;
    private String locationInHospital;
    private String description;
    private String contributingFactors;
    private String immediateActions;

    // People involved (stripped for anonymous reports)
    private String reportedByName;
    private String reportedByRole;
    private Instant reportedAt;
    private String involvedStaffNames;
    private Boolean patientHarmed;

    // Investigation
    private String investigatorName;
    private Instant investigationStartedAt;
    private String rootCauseAnalysis;
    private String rootCauseCategory;
    private Instant investigationCompletedAt;

    // Corrective action
    private String correctiveAction;
    private String correctiveActionOwner;
    private Instant correctiveActionDeadline;
    private Instant correctiveActionCompletedAt;
    private String preventiveMeasures;

    // Closure
    private Instant closedAt;
    private String closedByName;
    private String lessonsLearned;

    private boolean isAnonymous;
    private String notes;
    private Instant createdAt;
}
