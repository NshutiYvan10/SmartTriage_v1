package com.smartTriage.smartTriage_server.module.clinicalsigns.dto;

import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignCategory;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignStatus;
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
public class ClinicalSignEventResponse {
    private UUID id;
    private UUID visitId;
    private UUID patientId;
    private String signCode;
    private ClinicalSignCategory signCategory;
    private ClinicalSignStatus status;
    private Double numericValue;
    private String notes;
    private Instant recordedAt;
    private UUID recordedById;
    private String recordedByName;
    private boolean isBaseline;
}
