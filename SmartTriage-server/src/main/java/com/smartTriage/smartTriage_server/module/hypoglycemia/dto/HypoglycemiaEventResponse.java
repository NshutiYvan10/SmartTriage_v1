package com.smartTriage.smartTriage_server.module.hypoglycemia.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for hypoglycemia event data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HypoglycemiaEventResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;
    private String patientName;

    private String currentZone;

    private Instant detectedAt;
    private Double glucoseLevel;
    private String triggerReason;
    private String severity;
    private String glucoseSource;
    private boolean neonatal;
    private String detectedByName;
    private Instant recheckDueAt;

    private String treatmentGiven;
    private Instant treatmentGivenAt;
    private String treatmentGivenByName;

    private Double repeatGlucoseLevel;
    private Instant repeatGlucoseAt;

    private boolean resolved;
    private Instant resolvedAt;
    private String resolvedByName;
    private String notes;

    private Instant createdAt;
}
