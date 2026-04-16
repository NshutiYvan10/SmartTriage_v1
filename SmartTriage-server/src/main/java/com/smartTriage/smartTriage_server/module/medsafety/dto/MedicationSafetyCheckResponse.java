package com.smartTriage.smartTriage_server.module.medsafety.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a persisted medication safety check record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicationSafetyCheckResponse {

    private UUID id;
    private UUID visitId;
    private UUID medicationId;
    private Instant checkedAt;
    private String drugName;
    private Double prescribedDoseMg;
    private Double patientWeightKg;

    private boolean allergyCheckPassed;
    private String allergyWarning;

    private boolean doseCheckPassed;
    private String doseWarning;

    private boolean interactionCheckPassed;
    private String interactionWarning;

    private boolean duplicateTherapyCheckPassed;
    private String duplicateWarning;

    private boolean overallSafe;

    private String overriddenBy;
    private String overrideReason;
    private Instant overriddenAt;

    private Instant createdAt;
    private Instant updatedAt;
}
