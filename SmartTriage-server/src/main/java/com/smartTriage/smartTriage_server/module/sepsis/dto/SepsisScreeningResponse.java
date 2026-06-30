package com.smartTriage.smartTriage_server.module.sepsis.dto;

import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for a sepsis screening result.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SepsisScreeningResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;
    private String patientName;
    // Patient location context — denormalised so a sepsis card on the board
    // shows WHERE the patient is without a second fetch.
    private com.smartTriage.smartTriage_server.common.enums.EdZone currentZone;
    private String currentBedLabel;

    private Instant screenedAt;
    private String screenedByName;

    // Status and scores
    private SepsisStatus sepsisStatus;
    private int qsofaScore;
    private int sirsScore;

    // qSOFA criteria
    private boolean alteredMentation;
    private boolean respiratoryRateHigh;
    private boolean systolicBpLow;

    // SIRS criteria
    private boolean temperatureCriteriaMet;
    private boolean heartRateCriteriaMet;
    private boolean respiratoryRateCriteriaMet;
    private boolean wbcCriteriaMet;

    // Clinical context
    private String suspectedInfectionSource;
    private Double lactateLevel;
    private List<String> findings;

    // Bundle tracking
    private boolean bundleRequired;
    private Instant bundleStartedAt;
    private Instant bundleCompletedAt;
    private boolean bloodCultureObtained;
    private boolean broadSpectrumAntibiotics;
    private boolean ivCrystalloidBolus;
    private boolean lactateMeasured;
    private boolean vasopressorsIfNeeded;
    private boolean repeatLactateIfElevated;
    private int bundleItemsCompleted;
    private int bundleItemsTotal;

    // Pediatric safety + data quality
    private boolean pediatric;
    private String pediatricCaveat;
    private boolean insufficientData;
    private String dataQualityNote;

    // Time-stamped action trail
    private String bundleStartedByName;
    private String bundleCompletedByName;
    private Instant bloodCultureObtainedAt;
    private Instant broadSpectrumAntibioticsAt;
    private Instant ivCrystalloidBolusAt;
    private Instant lactateMeasuredAt;
    private Instant vasopressorsIfNeededAt;
    private Instant repeatLactateIfElevatedAt;

    private String notes;
    private Instant createdAt;
}
