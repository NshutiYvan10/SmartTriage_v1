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
    /**
     * Field named {@code hypoglycemic} + wire name pinned to {@code isHypoglycemic}: with
     * the old {@code isHypoglycemic} field name Jackson serialized the key as
     * {@code hypoglycemic}, so the frontend's {@code isHypoglycemic} read was always
     * undefined (the recurring isX trap — unnoticed here only because the panel used to
     * discard this response entirely).
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isHypoglycemic")
    private boolean hypoglycemic;
    private String severity;
    private String treatmentProtocol;
    private List<String> triggerReasons;

    /** Non-null if a hypoglycemia event was created */
    private UUID eventId;
}
