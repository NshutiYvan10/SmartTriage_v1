package com.smartTriage.smartTriage_server.module.medication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A pre-computed, EDITABLE prescription suggestion for one drug (or IV
 * fluid) and one patient. Everything here is a starting point the doctor
 * confirms or overrides — the prescribe form pre-fills from it, and the
 * medication-safety engine still validates whatever is finally submitted.
 *
 * <p>{@code rationale} carries the arithmetic in clinician-readable form
 * ("15 mg/kg × 14 kg = 210 mg") so the suggestion is transparent, never
 * a black box. {@code weightSource} makes estimated weights impossible
 * to miss.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoseSuggestionResponse {

    /** False when the formulary lacks suggestion data — form stays manual. */
    private boolean suggested;

    private String drugName;
    private Double doseValue;
    private String doseUnit;
    /** MedicationRoute enum name (e.g. "PO"), null when unknown. */
    private String route;
    /** Dosing interval in hours (q6h = 6); null for one-time. */
    private Double intervalHours;
    /** PrescriptionType enum name suggestion (SCHEDULED / ONE_TIME / CONTINUOUS). */
    private String prescriptionType;

    // ── IV-fluid specifics (null for drugs) ──
    private String fluidName;
    private Double volumeMl;
    private Double rateMlPerHour;
    private Double durationHours;

    // ── Provenance ──
    private Double weightUsedKg;
    /** MEASURED | ESTIMATED_BY_AGE | NONE */
    private String weightSource;
    private List<String> rationale;
    private List<String> warnings;
    /** Formulary prescriber note, when present. */
    private String note;
}
