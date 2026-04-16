package com.smartTriage.smartTriage_server.module.hypoglycemia.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for the hypoglycemia enforcement check.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HypoglycemiaCheckResponse {

    private UUID visitId;
    private boolean requiresCheck;
    private boolean checkMandatory;
    private Double glucoseValue;
    private boolean isHypoglycemic;
    private String severity;
    private String treatmentProtocol;
    private List<String> triggerReasons;

    /** Non-null if a hypoglycemia event was created */
    private UUID eventId;
}
