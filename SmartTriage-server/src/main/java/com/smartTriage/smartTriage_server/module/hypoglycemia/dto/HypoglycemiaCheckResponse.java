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

    // ── cross-source reading + staleness (glucose due-clock) ──

    /** Which door the interpreted reading came through: TRIAGE / VITALS / LAB / EVENT / RECHECK. */
    private String glucoseSource;
    /** When the interpreted reading was taken. */
    private java.time.Instant glucoseAt;
    /** How old the interpreted reading is, in minutes. */
    private Long readingAgeMinutes;
    /**
     * True when the reading is older than the patient's scheduled measurement
     * interval. A stale value must never reassure — severity is downgraded to
     * PENDING_CHECK and a recheck is demanded — but a stale LOW stays loud
     * (an untreated old low is more alarming, not less).
     */
    private boolean staleReading;
    /** Present when the patient is on a glucose measurement schedule (tier label + cadence). */
    private String monitoringTier;
    private Long monitoringIntervalMinutes;
    private java.time.Instant nextDueAt;
}
