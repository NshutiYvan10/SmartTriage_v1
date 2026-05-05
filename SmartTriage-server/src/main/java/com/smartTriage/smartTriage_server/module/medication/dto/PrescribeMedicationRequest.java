package com.smartTriage.smartTriage_server.module.medication.dto;

import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to prescribe/record a medication administration entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrescribeMedicationRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotBlank(message = "Drug name is required")
    private String drugName;

    private String dose;

    @NotNull(message = "Route is required")
    private MedicationRoute route;

    private String frequency;

    /** Optional: explicit prescriber name if not current user */
    private String prescribedByName;

    private String notes;

    // ────────── ALLERGY OVERRIDE (V23) ──────────
    // Populated by the frontend AllergyConfirmDialog when the prescriber
    // chose to prescribe despite a known allergy. Both fields are
    // nullable — for the common case (no conflict), the request body is
    // unchanged.

    /** TRUE when the prescriber acknowledged an allergy conflict. */
    private Boolean prescribedDespiteAllergy;

    /**
     * Free-text snapshot of the conflicts the dialog showed, e.g.
     * "penicillin (penicillins/beta-lactam); sulfa (sulfa drugs)".
     * Persisted verbatim so the audit record reflects exactly what the
     * prescriber saw at decision time.
     */
    private String allergyOverrideMatches;

    // ────────── INTERACTION OVERRIDE (V24) ──────────
    // Populated by the same PrescribeSafetyDialog when an interaction
    // conflict was acknowledged. Both nullable for the common no-
    // conflict case.

    /** TRUE when the prescriber acknowledged a drug–drug interaction. */
    private Boolean prescribedDespiteInteraction;

    /**
     * Free-text snapshot of the interactions the dialog showed, e.g.
     * "Warfarin 5mg + aspirin/warfarin: additive bleeding risk [major]".
     */
    private String interactionOverrideMatches;
}
