package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;

/**
 * V55 — Clinical rules about which {@link ShiftFunction} values may pair
 * with which {@link EdZone} values on a shift assignment (or template
 * assignment).
 *
 * <p>This is the canonical single source of truth used by:
 * <ul>
 *   <li>{@code ShiftTemplateService.validateAssignments} on template create/update</li>
 *   <li>{@code ShiftAssignmentService.assignToZone} on direct (non-template) assignment</li>
 *   <li>{@code ShiftAssignmentService.updateAssignment} on edits</li>
 * </ul>
 *
 * <p>The same predicate is mirrored in V55 as a Postgres CHECK constraint
 * (defence-in-depth) so a buggy service call can't bypass it.
 *
 * <h2>The rules</h2>
 * <table>
 *   <tr><th>ShiftFunction</th><th>Allowed zones</th></tr>
 *   <tr><td>TRIAGE_NURSE</td><td>TRIAGE only</td></tr>
 *   <tr><td>ZONE_NURSE</td><td>any zone except TRIAGE</td></tr>
 *   <tr><td>PRIMARY_DOCTOR</td><td>any zone except TRIAGE</td></tr>
 *   <tr><td>SUPERVISING_DOCTOR</td><td>any zone except TRIAGE</td></tr>
 *   <tr><td>RESIDENT</td><td>any zone except TRIAGE</td></tr>
 *   <tr><td>CHARGE_NURSE</td><td>any zone (works floor-wide)</td></tr>
 * </table>
 *
 * <h2>Why doctors are excluded from TRIAGE</h2>
 *
 * In the Rwandan ED workflow, triage is a nurse function (per the
 * RwandaTriageDecisionEngine documentation). A doctor assigned to
 * TRIAGE zone would conflict with the established workflow and the
 * triage-write authority gate ({@code ClinicalAuthz.callerCanPerformTriage})
 * which permits TRIAGE_NURSE + Charge Nurse only.
 */
public final class ShiftRoleZonePolicy {

    private ShiftRoleZonePolicy() {
        // Pure helper — no instances.
    }

    /**
     * Throws {@link ClinicalBusinessException} when the (function, zone) pair
     * violates the clinical rules. Otherwise returns silently.
     */
    public static void validate(ShiftFunction shiftFunction, EdZone zone) {
        if (shiftFunction == null) {
            throw new ClinicalBusinessException("Shift function is required.");
        }
        if (zone == null) {
            throw new ClinicalBusinessException("Zone is required.");
        }

        switch (shiftFunction) {
            case TRIAGE_NURSE -> {
                if (zone != EdZone.TRIAGE) {
                    throw new ClinicalBusinessException(
                            "Triage Nurse must be assigned to the TRIAGE zone. "
                                    + "Got: " + zone + ". Change the shift function "
                                    + "(e.g. ZONE_NURSE) or pick the TRIAGE zone.");
                }
            }
            case ZONE_NURSE, PRIMARY_DOCTOR, SUPERVISING_DOCTOR, RESIDENT -> {
                if (zone == EdZone.TRIAGE) {
                    throw new ClinicalBusinessException(
                            shiftFunction + " cannot be assigned to the TRIAGE zone. "
                                    + "Triage is a nurse function — assign this user "
                                    + "as TRIAGE_NURSE, or pick a treatment zone.");
                }
            }
            case CHARGE_NURSE -> {
                // Charge Nurse may operate from any zone — the role is
                // floor-wide. No constraint.
            }
        }
    }

    /**
     * Pure predicate version — returns true when the pair is allowed,
     * false when it would be rejected. Used by frontend-mirror code on
     * the backend (e.g. recommending corrections in error messages).
     */
    public static boolean isAllowed(ShiftFunction shiftFunction, EdZone zone) {
        try {
            validate(shiftFunction, zone);
            return true;
        } catch (ClinicalBusinessException e) {
            return false;
        }
    }
}
