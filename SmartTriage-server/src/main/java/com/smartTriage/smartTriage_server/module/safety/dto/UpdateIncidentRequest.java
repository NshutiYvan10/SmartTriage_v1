package com.smartTriage.smartTriage_server.module.safety.dto;

import com.smartTriage.smartTriage_server.common.enums.IncidentSeverity;
import com.smartTriage.smartTriage_server.common.enums.IncidentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Request DTO for updating an existing safety incident.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateIncidentRequest {

    private IncidentType incidentType;
    private IncidentSeverity severity;
    private Instant incidentDateTime;
    private String locationInHospital;
    private String description;
    private String contributingFactors;
    private String immediateActions;
    private String involvedStaffNames;
    private Boolean patientHarmed;
    private String notes;
}
