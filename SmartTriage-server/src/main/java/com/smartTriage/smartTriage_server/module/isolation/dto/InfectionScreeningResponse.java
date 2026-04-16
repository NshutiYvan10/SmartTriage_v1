package com.smartTriage.smartTriage_server.module.isolation.dto;

import com.smartTriage.smartTriage_server.common.enums.InfectionRiskLevel;
import com.smartTriage.smartTriage_server.common.enums.IsolationType;
import com.smartTriage.smartTriage_server.common.enums.NotifiableDisease;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for infection screening data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfectionScreeningResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;
    private String patientName;

    private Instant screenedAt;
    private String screenedByName;
    private InfectionRiskLevel riskLevel;
    private IsolationType isolationType;
    private String suspectedCondition;
    private NotifiableDisease notifiableDisease;

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

    // PPE requirements
    private boolean requiresN95;
    private boolean requiresGown;
    private boolean requiresGloves;
    private boolean requiresFaceShield;
    private boolean requiresApron;
    private boolean requiresBootCovers;

    // Isolation actions
    private String isolationRoomAssigned;
    private Instant isolationStartedAt;
    private Instant isolationEndedAt;
    private Instant publicHealthNotifiedAt;
    private String publicHealthReferenceNumber;

    private String notes;
    private List<String> findings;
    private Instant createdAt;
}
