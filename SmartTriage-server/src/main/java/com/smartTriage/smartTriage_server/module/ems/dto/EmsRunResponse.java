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

    private Instant createdAt;
    private Instant updatedAt;

    /** Itemised interventions, ordered chronologically. Optional in list views. */
    private List<EmsInterventionResponse> interventions;
}
