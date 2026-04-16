package com.smartTriage.smartTriage_server.module.safety.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for incident statistics.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentStatsResponse {

    private UUID hospitalId;
    private Instant from;
    private Instant to;
    private long totalIncidents;
    private Map<String, Long> countByType;
    private Map<String, Long> countBySeverity;
}
