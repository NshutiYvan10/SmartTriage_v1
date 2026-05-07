package com.smartTriage.smartTriage_server.module.visit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartTriage.smartTriage_server.common.enums.*;
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
public class VisitResponse {

    private UUID id;
    private String visitNumber;
    private UUID patientId;
    private String patientName;
    private UUID hospitalId;
    private ArrivalMode arrivalMode;
    private Instant arrivalTime;
    private String chiefComplaint;
    private VisitStatus status;
    private TriageCategory currentTriageCategory;
    private Integer currentTewsScore;
    private Instant triageTime;
    private Instant assessmentStartTime;
    private DispositionType dispositionType;
    private Instant dispositionTime;
    private String dispositionNotes;
    private String referringFacility;
    @JsonProperty("isPediatric")
    private boolean isPediatric;
    private int retriageCount;

    // ── Direct Resus Admission flags (V28) ──
    /**
     * TRUE when the visit was admitted to RESUS but no bed was available.
     * Frontend renders the resus-overflow banner + transfer prompt.
     */
    @JsonProperty("pendingResusOverflow")
    private boolean pendingResusOverflow;

    /**
     * TRUE when this visit was created from an ambulance call-ahead
     * before the patient physically arrived. Door clock has not started
     * unless arrivalConfirmedAt is non-null.
     */
    @JsonProperty("ambulancePreArrival")
    private boolean ambulancePreArrival;

    /**
     * For ambulance pre-arrival visits: when the patient was confirmed
     * to have physically arrived. NULL until confirmed. Used as the
     * door-clock anchor for arrival-time metrics.
     */
    private Instant arrivalConfirmedAt;

    private Instant createdAt;
    private Instant updatedAt;
}
