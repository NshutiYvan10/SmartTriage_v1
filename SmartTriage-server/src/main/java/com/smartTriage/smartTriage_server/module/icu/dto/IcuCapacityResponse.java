package com.smartTriage.smartTriage_server.module.icu.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for ICU bed capacity information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IcuCapacityResponse {

    private int totalBeds;
    private int occupiedBeds;
    private int availableBeds;
    private double occupancyPercent;
}
