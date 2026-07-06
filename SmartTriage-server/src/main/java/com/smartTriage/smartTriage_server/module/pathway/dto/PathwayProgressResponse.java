package com.smartTriage.smartTriage_server.module.pathway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartTriage.smartTriage_server.common.enums.PathwayActivationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Progress report for a pathway activation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathwayProgressResponse {

    private UUID activationId;
    private UUID pathwayId;
    private String pathwayName;
    private PathwayActivationStatus status;
    private Instant activatedAt;
    private int totalSteps;
    private int completedSteps;
    private int skippedSteps;
    private int pendingSteps;
    private double completionPercentage;
    private List<StepProgress> steps;
    private List<String> overdueSteps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepProgress {
        private UUID stepId;
        private Integer stepOrder;
        private String stepTitle;
        private String category;
        // Field `mandatory` + @JsonProperty("isMandatory") → exactly one JSON key the FE reads.
        // See PathwayStepResponse for why (field named `isMandatory` emitted two keys).
        @JsonProperty("isMandatory")
        private boolean mandatory;
        private Integer timeframeMinutes;
        private String status; // "COMPLETED", "SKIPPED", "PENDING", "OVERDUE"
        private Instant completedAt;
        private String completedByName;
        private Integer timeToCompleteMinutes;
        private String skipReason; // populated when status == "SKIPPED" — why a step was bypassed
    }
}
