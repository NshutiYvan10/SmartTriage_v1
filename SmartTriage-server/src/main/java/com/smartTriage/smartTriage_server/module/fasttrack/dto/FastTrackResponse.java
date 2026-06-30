package com.smartTriage.smartTriage_server.module.fasttrack.dto;

import com.smartTriage.smartTriage_server.common.enums.FastTrackStatus;
import com.smartTriage.smartTriage_server.common.enums.FastTrackType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for fast-track activation data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FastTrackResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;
    private String patientName;
    private UUID hospitalId;
    private String currentZone;
    private String currentBedLabel;

    private FastTrackType fastTrackType;
    private FastTrackStatus status;
    private Instant activatedAt;
    private String activatedByName;

    // Action trail (V75)
    private Instant acknowledgedAt;
    private String acknowledgedByName;
    private String lastUpdatedByName;
    private String completedByName;

    // Stroke-specific
    private Instant symptomOnsetTime;
    private String beFastScore;
    private Integer nihssScore;
    private Instant ctOrderedAt;
    private Instant ctCompletedAt;
    private String ctResult;
    private Boolean isHemorrhagic;
    private Boolean thrombolysisEligible;
    private String thrombolysisAdvisory;
    private Instant thrombolysisStartedAt;
    private Integer doorToCtMinutes;

    // MI-specific
    private Instant chestPainOnsetTime;
    private Instant ecgOrderedAt;
    private Instant ecgCompletedAt;
    private String ecgResult;
    private Boolean stElevation;
    private Boolean troponinOrdered;
    private Double troponinResult;
    private Instant troponinResultedAt;
    private Boolean aspirinGiven;
    private Instant aspirinGivenAt;
    private Boolean anticoagulantGiven;
    private Boolean referredForPci;
    private Instant referredForPciAt;
    private Integer doorToEcgMinutes;
    private Integer doorToNeedleMinutes;

    // Outcome
    private Instant completedAt;
    private String outcome;
    private String notes;

    private Instant createdAt;
}
