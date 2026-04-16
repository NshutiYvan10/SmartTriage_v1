package com.smartTriage.smartTriage_server.module.safety.dto;

import com.smartTriage.smartTriage_server.common.enums.IncidentSeverity;
import com.smartTriage.smartTriage_server.common.enums.IncidentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO for reporting a new patient safety incident.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportIncidentRequest {

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    private UUID visitId;

    @NotNull(message = "Incident type is required")
    private IncidentType incidentType;

    @NotNull(message = "Severity is required")
    private IncidentSeverity severity;

    @NotNull(message = "Incident date/time is required")
    private Instant incidentDateTime;

    private String locationInHospital;

    @NotBlank(message = "Description is required")
    private String description;

    private String contributingFactors;
    private String immediateActions;

    @NotBlank(message = "Reporter name is required")
    private String reportedByName;

    private String reportedByRole;
    private String involvedStaffNames;
    private Boolean patientHarmed;
    private boolean isAnonymous;
    private String notes;
}
