package com.smartTriage.smartTriage_server.module.icu.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.IcuEscalationStatus;
import com.smartTriage.smartTriage_server.common.enums.IcuTriggerType;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for ICU escalation details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IcuEscalationResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;
    private String patientName;
    private TriageCategory triageCategory;

    // ── Denormalised patient CURRENT physical location (board display) ──
    // Where the patient is RIGHT NOW in the ED — distinct from the ICU
    // destination (icuBedNumber). A clinician scanning the escalation
    // board must see who+where without a second fetch. Populated from
    // visit.currentEdZone / visit.currentBed.code (null-safe).
    private EdZone currentEdZone;
    private String currentBed;

    private String escalationReason;
    private IcuTriggerType triggerType;
    private Instant escalatedAt;
    private String escalatedByName;
    private boolean automatic;

    private Instant icuTeamNotifiedAt;
    private String icuConsultant;
    private Instant icuRespondedAt;
    private Integer icuResponseMinutes;
    private Boolean icuBedAvailable;
    private String icuBedNumber;
    private Instant icuBedAssignedAt;

    private Instant stabilizationStartedAt;
    private String stabilizationNotes;
    private Boolean intubationRequired;
    private Boolean vasopressorsRequired;
    private Boolean mechanicalVentilation;

    private IcuEscalationStatus status;
    private String declineReason;
    private Instant transferredAt;
    private String alternativePlan;
    private String outcome;
    private String notes;

    private Instant createdAt;
}
