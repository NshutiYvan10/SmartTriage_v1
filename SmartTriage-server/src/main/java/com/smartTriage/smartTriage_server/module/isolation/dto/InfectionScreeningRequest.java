package com.smartTriage.smartTriage_server.module.isolation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for infection screening.
 * Captures all symptom and exposure data for infection risk assessment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfectionScreeningRequest {

    private String screenedByName;

    // Screening criteria
    private boolean hasFever;
    private boolean hasCough;
    private Integer hasCoughDurationWeeks;
    private boolean hasNightSweats;
    private boolean hasWeightLoss;
    private boolean hasRash;
    private boolean hasDiarrhea;
    private boolean hasRecentTravel;
    private String recentTravelLocation;
    private boolean hasContactWithInfectious;
    private String contactDetails;
    private boolean hasBleedingSymptoms;
    private boolean isHealthcareWorker;

    private String notes;
}
