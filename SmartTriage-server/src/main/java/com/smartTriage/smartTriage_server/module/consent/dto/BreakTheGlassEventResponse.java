package com.smartTriage.smartTriage_server.module.consent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Governance view of a break-the-glass emergency override (Phase 3). Carries the forensic facts a
 * governance reviewer needs to hold the clinician accountable — actor, reason, the consent state
 * that was overridden, when — plus the review/sign-off overlay. The patient is shown only by a
 * MASKED national ID (governance is auditing the clinician, not browsing the patient's record).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BreakTheGlassEventResponse {

    private UUID id;
    private UUID personIdentityId;
    private String maskedNationalId;

    // Actor (the clinician who overrode) — the accountable party.
    private UUID actorUserId;
    private String actorName;
    private String actorRole;
    private UUID actorHospitalId;

    // Forensic facts (immutable).
    private String reason;
    /** Consent state at override time: NONE | DENIED | WITHDRAWN. */
    private String priorConsentState;
    private Instant accessedAt;

    // Governance review overlay.
    private boolean acknowledged;
    private String acknowledgedByName;
    private Instant acknowledgedAt;
    private String acknowledgmentNote;
}
