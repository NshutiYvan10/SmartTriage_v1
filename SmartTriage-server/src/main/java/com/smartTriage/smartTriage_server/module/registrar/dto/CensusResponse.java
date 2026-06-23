package com.smartTriage.smartTriage_server.module.registrar.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Point-in-time department census (R11) — total active patients with breakdowns by visit status
 * and by ED zone. Computed live from active visits; no historical time-series.
 */
@Builder
public record CensusResponse(
        int totalActive,
        Map<String, Integer> byStatus,
        Map<String, Integer> byZone,
        Instant generatedAt) {
}
