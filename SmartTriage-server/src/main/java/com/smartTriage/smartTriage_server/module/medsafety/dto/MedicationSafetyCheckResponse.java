package com.smartTriage.smartTriage_server.module.medsafety.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
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

    // ── Denormalised patient context (safety-check list display) ──
    // The Safety Checks list previously showed only a weight value
    // labelled "Patient:", with no identity or location. Every
    // patient-scoped row MUST show WHO the check is for and WHERE that
    // patient is. Populated from visit → patient / currentEdZone /
    // currentBed (NULL-SAFE).
    private String patientName;
    private String visitNumber;
    private EdZone currentZone;
    private String currentBedLabel;

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
