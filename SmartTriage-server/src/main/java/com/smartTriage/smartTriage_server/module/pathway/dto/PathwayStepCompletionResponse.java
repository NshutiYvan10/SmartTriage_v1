package com.smartTriage.smartTriage_server.module.pathway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathwayStepCompletionResponse {

    private UUID id;
    private UUID activationId;
    private UUID stepId;
    private String stepTitle;
    private Integer stepOrder;
    private Instant completedAt;
    private String completedByName;
    private boolean wasSkipped;
    private String skipReason;
    private String notes;
    private Integer timeToCompleteMinutes;
}
