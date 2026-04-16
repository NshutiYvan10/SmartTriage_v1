package com.smartTriage.smartTriage_server.module.sepsis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional override fields for a sepsis screening request.
 * When provided, these values supplement the vital signs data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SepsisScreeningRequest {

    /** Suspected source of infection (e.g., "Respiratory", "Urinary", "Abdominal") */
    private String suspectedInfectionSource;

    /** Lactate level in mmol/L (from lab results) */
    private Double lactateLevel;

    /** WBC count (cells/µL) — if available from lab investigations */
    private Double wbcCount;

    /** Whether WBC bands are > 10% */
    private Boolean wbcBandsElevated;

    /** Additional clinical notes */
    private String notes;
}
