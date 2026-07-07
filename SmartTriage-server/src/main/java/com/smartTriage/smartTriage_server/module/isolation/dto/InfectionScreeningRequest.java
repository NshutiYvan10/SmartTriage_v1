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

    /**
     * Healthcare worker (occupational-exposure risk). Field is named {@code healthcareWorker}
     * (not {@code isHealthcareWorker}) so Lombok's {@code isHealthcareWorker()} getter and the
     * setter couple into ONE Jackson property; {@code @JsonProperty} pins the wire name the
     * frontend has always sent. With the old {@code isHealthcareWorker} field name, Jackson
     * derived the property {@code healthcareWorker} — the FE's {@code isHealthcareWorker} key
     * matched nothing and the flag was silently dropped (always false in the IDSR record).
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isHealthcareWorker")
    private boolean healthcareWorker;

    /** Immunocompromised (e.g. advanced HIV, chemotherapy, transplant) — drives PROTECTIVE (reverse) isolation. */
    private boolean immunocompromised;

    /** Neck stiffness / meningism — strengthens the meningococcal suspicion. */
    private boolean hasNeckStiffness;

    private String notes;
}
