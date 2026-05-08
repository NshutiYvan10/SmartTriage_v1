package com.smartTriage.smartTriage_server.module.ems.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Update mutable fields on an EmsRun mid-run. Null = no change.
 *
 * Used by the paramedic's multi-step form as they fill in vitals,
 * field triage, etc. Status transitions are handled by dedicated
 * endpoints (preregister, confirmArrival, transferOfCare).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEmsRunRequest {

    private String unitCallsign;
    private String paramedicName;

    private Integer patientAgeYears;
    private String patientSex;
    private String incidentLocation;
    private String mechanism;
    private String historySummary;
    private String injuriesObserved;

    private String fieldTriageCategory;
    private String fieldTriageReason;

    private Integer fieldGcs;
    private Integer fieldRespRate;
    private Integer fieldHr;
    private Integer fieldSbp;
    private Integer fieldDbp;
    private Integer fieldSpo2;
    private BigDecimal fieldTemp;
    private BigDecimal fieldGlucose;

    private Integer etaMinutes;
    private String notes;
}
