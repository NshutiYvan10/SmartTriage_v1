package com.smartTriage.smartTriage_server.module.sepsis.dto;

import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for the sepsis bundle compliance status.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SepsisBundleStatusResponse {

    private UUID screeningId;
    private UUID visitId;
    private String patientName;
    private SepsisStatus sepsisStatus;

    // Bundle timing
    private Instant bundleStartedAt;
    private Instant bundleCompletedAt;
    private long minutesSinceBundleStart;
    private boolean isBundleOverdue;
    private boolean isBundleComplete;

    // Bundle items
    private boolean bloodCultureObtained;
    private boolean broadSpectrumAntibiotics;
    private boolean ivCrystalloidBolus;
    private boolean lactateMeasured;
    private boolean vasopressorsIfNeeded;
    private boolean repeatLactateIfElevated;

    private int itemsCompleted;
    private int totalItems;
    private double compliancePercentage;
}
