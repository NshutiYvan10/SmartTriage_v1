package com.smartTriage.smartTriage_server.module.pathway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A pathway recommendation from the recommendation engine.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathwayRecommendation {

    private UUID pathwayId;
    private String pathwayCode;
    private String pathwayName;
    private String reason;
    private String urgency; // "HIGH", "MEDIUM", "LOW"
    private double confidence;
}
