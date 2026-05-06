package com.smartTriage.smartTriage_server.module.medsafety.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a drug formulary entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DrugFormularyResponse {

    private UUID id;
    private String genericName;
    private String brandNames;
    private String drugClass;
    private String atcCode;
    private String remlCategory;

    // Dosing
    private Double adultMinDoseMg;
    private Double adultMaxDoseMg;
    private Double adultMaxDailyDoseMg;
    private Double pediatricMinDoseMgPerKg;
    private Double pediatricMaxDoseMgPerKg;
    private Double pediatricMaxDailyDoseMgPerKg;
    /** Unit for the numeric dose ranges. See DrugFormulary.doseUnit. */
    private String doseUnit;
    private Double geriatricAdjustmentPercent;
    private boolean renalAdjustmentRequired;
    private boolean hepaticAdjustmentRequired;

    // Routes
    private String availableRoutes;

    // Interactions
    private String contraindications;
    private String majorInteractions;
    private String allergenGroups;

    // Safety
    private boolean isHighAlert;
    private boolean requiresDoubleCheck;
    private String blackBoxWarning;
    private String pregnancyCategory;

    private boolean isOnReml;

    private UUID hospitalId;

    private Instant createdAt;
    private Instant updatedAt;
}
