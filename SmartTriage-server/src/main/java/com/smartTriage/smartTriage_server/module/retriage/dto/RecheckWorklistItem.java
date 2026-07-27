package com.smartTriage.smartTriage_server.module.retriage.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the vitals-round worklist (Vitals Rounds page): a patient
 * whose reassessment clock is running, with everything the round nurse
 * needs — who, where, when last assessed, when next due, and whether a
 * spot-check is already in progress.
 *
 * <p>Patients on an active CONTINUOUS monitoring session are excluded
 * upstream (their stream is the reassessment); a patient mid-SPOT_CHECK
 * appears with {@code checkInProgress = true} so two nurses don't wheel
 * two carts to the same chair.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecheckWorklistItem {

    private UUID visitId;
    private String visitNumber;
    private String patientName;
    /**
     * Field is named {@code pediatric} (not {@code isPediatric}) on
     * purpose — the isX-field Jackson trap: a boolean field literally
     * named {@code isX} serializes as {@code x}. The @JsonProperty pins
     * the wire name the frontend reads.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isPediatric")
    private boolean pediatric;

    private TriageCategory category;
    private Integer tewsScore;
    private EdZone zone;
    /** Bed/space label when placed; null for chair-based zones. */
    private String bedCode;

    /** Basis of the clock: latest of last triage and last recorded vitals. */
    private Instant lastAssessedAt;
    private Instant nextDueAt;
    /** Ratified recheck interval for the category (minutes). */
    private int intervalMinutes;
    /** Minutes until due; negative = overdue by that many minutes. */
    private long minutesUntilDue;
    private boolean overdue;

    /** An active spot-check session already exists for this visit. */
    private boolean checkInProgress;
}
