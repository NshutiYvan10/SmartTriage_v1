package com.smartTriage.smartTriage_server.module.medication.dto;

import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Full response DTO for a Medication Administration Record (MAR) entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicationResponse {

    private UUID id;
    private UUID visitId;

    // Drug details
    private String drugName;
    private String dose;
    private MedicationRoute route;
    private String frequency;

    // Prescribing chain
    private UUID prescribedById;
    private String prescribedByName;
    private Instant prescribedAt;

    // Administration chain
    private UUID administeredById;
    private String administeredByName;
    private Instant administeredAt;

    // Countersigning chain
    private UUID countersignedById;
    private String countersignedByName;
    private Instant countersignedAt;

    // Status & notes
    private MedicationStatus status;
    private String notes;

    // Allergy override (V23) — exposed so the frontend can render a
    // visible badge on overridden orders. Other clinicians coming into
    // the case need to see at a glance "this drug was prescribed
    // against a known allergy."
    private Boolean prescribedDespiteAllergy;
    private String allergyOverrideMatches;
    private Instant allergyOverrideAcknowledgedAt;

    // Interaction override (V24) — same rationale as the allergy
    // override fields, but for drug–drug interaction conflicts. A
    // single order can carry both flags.
    private Boolean prescribedDespiteInteraction;
    private String interactionOverrideMatches;
    private Instant interactionOverrideAcknowledgedAt;

    // Audit
    private Instant createdAt;
    private Instant updatedAt;
}
