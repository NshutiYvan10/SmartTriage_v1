package com.smartTriage.smartTriage_server.module.ems.dto;

import com.smartTriage.smartTriage_server.common.enums.EmsRunStatus;
import com.smartTriage.smartTriage_server.common.enums.EmsService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmsRunResponse {

    private UUID id;
    private UUID hospitalId;
    private UUID visitId;
    /** Linked patient (null for a pre-arrival run with no visit yet). */
    private UUID patientId;
    /** Patient display name once a visit is linked; null for an unlinked pre-arrival run. */
    private String patientName;
    private String visitNumber;
    private UUID paramedicUserId;
    private String paramedicName;

    private EmsService service;
    private String unitCallsign;

    private Instant dispatchedAt;
    private Instant sceneArrivedAt;
    private Instant sceneLeftAt;
    private Instant edArrivedAt;
    private Instant handedOffAt;
    private Instant cancelledAt;
    private String cancelReason;

    private Integer patientAgeYears;
    private String patientSex;
    private String incidentLocation;
    private String mechanism;
    private String historySummary;
    private String injuriesObserved;

    private String fieldTriageCategory;
    private String fieldTriageReason;
    private Integer fieldTewsScore;
    private String fieldTriageDecisionPath;
    private Boolean fieldTriageIsChild;
    private String fieldTriageInput;

    private Integer fieldGcs;
    private Integer fieldRespRate;
    private Integer fieldHr;
    private Integer fieldSbp;
    private Integer fieldDbp;
    private Integer fieldSpo2;
    private BigDecimal fieldTemp;
    private BigDecimal fieldGlucose;

    private EmsRunStatus status;

    private UUID handedOffToUserId;
    private String handedOffToName;
    private String handoverAcknowledgementText;

    private Integer etaMinutes;
    private String notes;

    private boolean lightsActive;
    private Instant lightsActivatedAt;

    private Instant preArrivalAckedAt;
    private String preArrivalAckedByName;

    private Instant arrivalAckedAt;
    private String arrivalAckedByName;

    /** When the ED formal-triage becomes due for this arrived patient (visit.edRetriageDueAt) —
     *  drives the "ED triage due in M:SS / OVERDUE" countdown on the case card. Null once triaged. */
    private Instant edRetriageDueAt;

    /**
     * Explicit case-lifecycle stage the dashboard renders as a stepper — the SINGLE
     * source of truth for "where is this ambulance case". Derived server-side from
     * status + arrivalAckedAt so every surface agrees:
     * DISPATCHED → EN_ROUTE → AT_DOOR → RECEIVED → HANDED_OFF (or CANCELLED).
     */
    private String lifecycleStage;

    /**
     * Where the patient is headed under the acuity-split policy, for the card's
     * routing badge: a treatment zone name (RESUS/ACUTE for RED/ORANGE) or
     * "TRIAGE_QUEUE" (YELLOW/GREEN/BLUE), or null when not yet field-triaged.
     */
    private String routingTarget;

    private Instant createdAt;
    private Instant updatedAt;

    /** Itemised interventions, ordered chronologically. Optional in list views. */
    private List<EmsInterventionResponse> interventions;
}
