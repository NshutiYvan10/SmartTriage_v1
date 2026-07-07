package com.smartTriage.smartTriage_server.module.override.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the unified Override Register — a single safety-gate bypass, normalised
 * across every override type in the system (medication safety check, lab verification
 * bypass, dose-administration gate override, emergency approval-gate skip,
 * prescribe-despite-allergy, prescribe-despite-interaction, and break-the-glass record
 * access). Every row answers the four incident-investigation questions:
 * WHO ({@code actorName}/{@code actorRole}), ON WHOM ({@code patientName}/{@code visitNumber},
 * or a masked national id for privacy break-the-glass), WHEN ({@code occurredAt}), and
 * WHY ({@code justification}). {@code detail} carries WHAT was overridden.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverrideRecordResponse {

    /** Stable machine key: MED_SAFETY_CHECK / LAB_VERIFICATION_BYPASS / DOSE_ADMINISTRATION /
     *  EMERGENCY_APPROVAL / PRESCRIBE_ALLERGY / PRESCRIBE_INTERACTION / BREAK_THE_GLASS. */
    private String overrideType;
    /** Grouping: "Medication" | "Lab" | "Privacy". */
    private String category;
    /** Human-readable event label ("Prescribed despite documented allergy"). */
    private String label;

    // WHO
    private String actorName;
    private String actorRole;   // role held AT THE TIME of the override
    private UUID actorUserId;   // forensic linkage (audit_logs.actor_user_id) — null on legacy rows

    // ON WHOM
    private String patientName;   // null for break-the-glass (privacy) — see maskedSubject
    private String visitNumber;
    private UUID visitId;
    private UUID patientId;
    private String maskedSubject; // break-the-glass: "National ID ***2780" (no visit context)

    // WHEN
    private Instant occurredAt;

    // WHY / WHAT
    private String justification; // the reason the clinician gave
    private String detail;        // what was overridden (drug + allergen list, test, warning…)
    private String severity;      // CRITICAL / HIGH where the source carries it; else null

    // Governance sign-off (where the override type supports being "reviewed")
    private boolean governanceAcknowledged;
    private String acknowledgedByName;
    private Instant acknowledgedAt;

    /** Id of the underlying domain row (for drill-down / de-dup). */
    private UUID sourceId;
}
