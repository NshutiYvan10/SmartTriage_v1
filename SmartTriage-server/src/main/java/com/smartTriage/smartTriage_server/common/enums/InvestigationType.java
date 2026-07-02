package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Types of clinical investigations ordered in the ED.
 */
@Getter
@RequiredArgsConstructor
public enum InvestigationType {

    LABORATORY("Laboratory Test"),
    RADIOLOGY("Radiology / Imaging"),
    ECG("Electrocardiogram"),
    ULTRASOUND("Ultrasound"),
    CT_SCAN("CT Scan"),
    MRI("MRI"),
    XRAY("X-Ray"),
    BLOOD_GAS("Blood Gas Analysis"),
    URINALYSIS("Urinalysis"),
    RAPID_TEST("Rapid Diagnostic Test"),
    POINT_OF_CARE("Point-of-Care Test"),
    OTHER("Other");

    private final String description;

    /**
     * Whether an investigation of this type is routed to the laboratory — i.e. it
     * spawns a LabOrder the lab owns and drives through its own lifecycle. The single
     * source of truth for both the order-time bridge (InvestigationService) and the
     * doctor-chart UI gating (so the chart does not offer specimen/result actions for
     * an investigation the lab owns, which would desync the two records).
     */
    public boolean isLabRoutable() {
        return this == LABORATORY || this == BLOOD_GAS || this == URINALYSIS || this == RAPID_TEST;
    }

    /**
     * Whether an investigation of this type must appear on the shared
     * <b>Imaging &amp; Diagnostics worklist</b> — a cross-patient queue a technician
     * (lab/diagnostics tech or a nurse) works through, performing the study and
     * recording the report.
     *
     * <p>These types need a human at a machine (radiographer / sonographer / ECG),
     * but SmartTriage has no separate radiographer role, so — exactly like a lab
     * order lands in the lab inbox — they land in the diagnostics worklist rather
     * than vanishing into a chart no one is monitoring. Without this an ordered
     * chest X-ray reaches NO technician: the "silent failure" the clinical-safety
     * standard forbids.
     *
     * <p>Deliberately EXCLUDES {@code POINT_OF_CARE} (performed at the bedside by
     * the ordering clinician — no worklist) and {@code OTHER} (undefined modality,
     * left to the ordering doctor's own roll-up). Lab-routable types are excluded
     * too — they have their own inbox ({@link #isLabRoutable()}).
     */
    public boolean needsDiagnosticsWorklist() {
        return this == XRAY || this == CT_SCAN || this == MRI
                || this == ULTRASOUND || this == RADIOLOGY || this == ECG;
    }
}
