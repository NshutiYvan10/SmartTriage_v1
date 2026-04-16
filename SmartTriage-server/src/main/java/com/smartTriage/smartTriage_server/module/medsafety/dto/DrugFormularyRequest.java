package com.smartTriage.smartTriage_server.module.medsafety.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to add or update a drug formulary entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DrugFormularyRequest {

    @NotBlank(message = "Generic name is required")
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
    private Double geriatricAdjustmentPercent;
    private Boolean renalAdjustmentRequired;
    private Boolean hepaticAdjustmentRequired;

    // Routes
    private String availableRoutes;

    // Interactions
    private String contraindications;
    private String majorInteractions;
    private String allergenGroups;

    // Safety
    private Boolean isHighAlert;
    private Boolean requiresDoubleCheck;
    private String blackBoxWarning;
    private String pregnancyCategory;

    private Boolean isOnReml;

    /** Hospital ID — null means system-wide entry */
    private UUID hospitalId;
}
