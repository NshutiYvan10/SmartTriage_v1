package com.smartTriage.smartTriage_server.module.clinical.dto;

import com.smartTriage.smartTriage_server.common.enums.DiagnosisType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a diagnosis record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisResponse {

    private UUID id;
    private UUID visitId;
    private DiagnosisType diagnosisType;
    private String icdCode;
    private String description;
    private String diagnosedByName;
    private Instant diagnosedAt;
    private Boolean isPrimary;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
