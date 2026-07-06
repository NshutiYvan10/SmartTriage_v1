package com.smartTriage.smartTriage_server.module.sepsis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Read-only suggestions that pre-fill the sepsis screening "Add labs" form from
 * lab data already recorded elsewhere (resulted Investigations + lab-panel
 * analytes), so a clinician need not re-key lactate / WBC. This NEVER auto-drives
 * a screening — the clinician reviews and confirms the values before running one.
 * Any field may be null when no matching resulted value exists for the visit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SepsisLabSuggestionsResponse {

    /** Latest lactate found for the visit (normalised to mmol/L), or null. */
    private LabSuggestion lactate;

    /** Latest WBC found for the visit (normalised to an absolute count, cells/µL), or null. */
    private LabSuggestion wbc;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LabSuggestion {
        /** The value exactly as recorded in the source. */
        private Double value;
        /** The unit exactly as recorded in the source (may be null/blank). */
        private String unit;
        /** {@link #value} converted to the unit the sepsis engine expects. */
        private Double normalizedValue;
        /** The unit {@link #normalizedValue} is expressed in ("mmol/L" or "cells/µL"). */
        private String normalizedUnit;
        /** Human-readable provenance, e.g. "Lab: CBC — WBC" or "Investigation: Point-of-care lactate". */
        private String source;
        /** When the source value was resulted (for showing age / picking the most recent). */
        private Instant resultedAt;
        /**
         * True when the source unit could not be confidently interpreted, so the
         * normalisation is a best-effort inference. The UI flags this and the
         * clinician must verify before screening; the server-side WBC plausibility
         * floor remains the final backstop.
         */
        private boolean needsUnitConfirmation;
    }
}
