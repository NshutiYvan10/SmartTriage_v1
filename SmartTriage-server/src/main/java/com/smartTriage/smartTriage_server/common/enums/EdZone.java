package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Emergency Department functional zones — per Rwanda ED practice with
 * KFH as the primary reference and graceful degradation for district
 * hospitals.
 *
 * <p>Two zone-mapping methods are exposed, deliberately separate:
 * <ul>
 *   <li>{@link #fromTriageCategory(TriageCategory)} — the simple
 *       category-only mapping used by alert routing pipelines that
 *       just want to know "which zone's doctors should see this
 *       alert". Stable signature; many call sites depend on it.</li>
 *   <li>{@link #forPatientPlacement(TriageCategory, boolean, boolean,
 *       boolean)} — the full clinical decision used to set
 *       {@code visits.current_ed_zone}. Takes the pediatric flag and
 *       hospital configuration so peds + AMBULATORY routing behave
 *       correctly per facility.</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum EdZone {
    RESUS("Resuscitation", "RED patients — immediate life-saving interventions"),
    ACUTE("Acute Treatment", "ORANGE patients — urgent, must be seen within 10 minutes"),
    GENERAL("General / Sub-Acute", "YELLOW & GREEN patients — assessment and treatment"),
    /**
     * Optional ambulatory zone for hospitals that physically separate
     * GREEN walk-ins from YELLOW bed-occupying patients. Tertiary EDs
     * (e.g. KFH) typically provision a few AMBULATORY beds; district
     * hospitals don't, in which case GREEN routes to GENERAL.
     */
    AMBULATORY("Ambulatory", "GREEN walk-in patients — streaming area, often discharged after one consult"),
    TRIAGE("Triage Station", "Triage nurse station — initial assessment"),
    OBSERVATION("Observation Unit", "Short-stay monitoring post-treatment"),
    ISOLATION("Isolation", "Infectious disease isolation area"),
    PEDIATRIC("Pediatric", "Dedicated pediatric treatment area");

    private final String label;
    private final String description;

    /**
     * Simple category-only mapping. Used by alert-routing pipelines
     * (AlertEscalationService, ContinuousMonitoringEngine) that need
     * to know which zone owns an alert independent of patient
     * pediatric flag or hospital config. Patient placement uses
     * {@link #forPatientPlacement} instead.
     */
    public static EdZone fromTriageCategory(TriageCategory category) {
        return switch (category) {
            case RED -> RESUS;
            case ORANGE -> ACUTE;
            case YELLOW, GREEN -> GENERAL;
            case BLUE -> GENERAL; // DOA — handled separately
        };
    }

    /**
     * Patient-placement decision. Used by TriageService and
     * ZoneTransferService when setting {@code visits.current_ed_zone}.
     *
     * <p>Decision matrix:
     * <table>
     *   <tr><th>Category</th><th>Pediatric?</th><th>Has peds resus?</th><th>Has ambulatory?</th><th>Zone</th></tr>
     *   <tr><td>RED</td><td>no</td><td>—</td><td>—</td><td>RESUS</td></tr>
     *   <tr><td>RED</td><td>yes</td><td>true</td><td>—</td><td>PEDIATRIC</td></tr>
     *   <tr><td>RED</td><td>yes</td><td>false</td><td>—</td><td>RESUS *</td></tr>
     *   <tr><td>ORANGE</td><td>no</td><td>—</td><td>—</td><td>ACUTE</td></tr>
     *   <tr><td>ORANGE/YELLOW</td><td>yes</td><td>—</td><td>—</td><td>PEDIATRIC</td></tr>
     *   <tr><td>YELLOW</td><td>no</td><td>—</td><td>—</td><td>GENERAL</td></tr>
     *   <tr><td>GREEN</td><td>yes</td><td>—</td><td>—</td><td>PEDIATRIC</td></tr>
     *   <tr><td>GREEN</td><td>no</td><td>—</td><td>true</td><td>AMBULATORY</td></tr>
     *   <tr><td>GREEN</td><td>no</td><td>—</td><td>false</td><td>GENERAL</td></tr>
     *   <tr><td>BLUE</td><td>—</td><td>—</td><td>—</td><td>GENERAL</td></tr>
     * </table>
     *
     * <p>* Pediatric RED with no peds resus capability still routes to
     * main RESUS — that's where the equipment lives. Under-routing a
     * critical paeds patient to a peds zone without a defibrillator is
     * the failure mode we're guarding against.
     *
     * @param category the patient's current triage category
     * @param isPediatric true when the visit is on a pediatric form
     * @param hospitalHasPediatricResus the hospital's
     *        {@code has_pediatric_resus} configuration flag
     * @param hospitalHasAmbulatoryZone true when the hospital has at
     *        least one bed with {@code zone = AMBULATORY}; deferred
     *        derivation lives in the caller (typically
     *        {@code BedRepository.existsByHospitalAndZone(...)})
     */
    public static EdZone forPatientPlacement(
            TriageCategory category,
            boolean isPediatric,
            boolean hospitalHasPediatricResus,
            boolean hospitalHasAmbulatoryZone) {
        if (category == null) return TRIAGE;

        if (category == TriageCategory.RED) {
            if (isPediatric && hospitalHasPediatricResus) return PEDIATRIC;
            return RESUS;
        }

        if (isPediatric) {
            // Non-RED peds always to PEDIATRIC (when the hospital has
            // a peds zone — caller is expected to have confirmed). For
            // hospitals with no peds zone at all, the bed inventory
            // forces routing to GENERAL via the placement override
            // path; that's a known edge case for very small facilities.
            return PEDIATRIC;
        }

        return switch (category) {
            case ORANGE -> ACUTE;
            case YELLOW -> GENERAL;
            case GREEN -> hospitalHasAmbulatoryZone ? AMBULATORY : GENERAL;
            case BLUE -> GENERAL;
            default -> GENERAL;
        };
    }
}
