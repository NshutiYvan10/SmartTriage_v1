package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.ChronicConditionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientChronicConditionResponse {

    private UUID id;
    private UUID patientId;

    /** Curated catalog short-code (HTN, T2DM, CKD, …) — null for free-text. */
    private String conditionCode;
    private String conditionName;

    private ChronicConditionStatus status;
    private String statusLabel;

    private String notes;
    private LocalDate onsetDate;

    private String recordedByName;
    private Instant recordedAt;

    private String resolvedByName;
    private Instant resolvedAt;
    private String resolveReason;
}
