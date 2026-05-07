package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Professional designation / seniority title.
 *
 * <p>Designations are set by admins and shown next to the user's name
 * everywhere in the system (triage records, clinical notes, alerts, etc.).
 * Grouped by the Role they typically pair with, but stored independently.
 *
 * <p><b>Permission implications:</b> most designations are display-only,
 * but a few carry unit-management authority that matches real-world ED
 * practice in Rwanda:
 *
 * <ul>
 *   <li>{@link #CHARGE_NURSE} — may assign staff to zones and manage shift
 *       assignments for their own hospital. See
 *       {@code ShiftAssignmentAuthz.canAssign}. This mirrors the actual
 *       role of a charge nurse at CHUK / KFH / RMH: they run the floor,
 *       decide who covers RESUS vs ACUTE vs PEDIATRIC, and hand over
 *       responsibility at shift changeover.</li>
 * </ul>
 *
 * <p>All other designations remain display-only and do not affect access.
 */
@Getter
@RequiredArgsConstructor
public enum Designation {

    // ── Doctor designations ──
    ED_HEAD("ED Head", Role.DOCTOR),
    CONSULTANT("Consultant", Role.DOCTOR),
    SENIOR_MEDICAL_OFFICER("Senior Medical Officer", Role.DOCTOR),
    MEDICAL_OFFICER("Medical Officer", Role.DOCTOR),
    RESIDENT("Resident", Role.DOCTOR),
    INTERN("Intern", Role.DOCTOR),

    // ── Nurse designations ──
    // All nurses fall under Role.NURSE. The designation captures the
    // long-term function the nurse performs:
    //   CHARGE_NURSE — unit management (see class doc for permission lift)
    //   TRIAGE_NURSE — intake/assessment specialist. Re-introduced after
    //                  V39 collapsed TRIAGE_NURSE the *Role* into NURSE.
    //                  Many EDs dedicate specific staff to triage as a
    //                  long-term assignment, distinct from the per-shift
    //                  ShiftFunction.TRIAGE_NURSE which records who is
    //                  AT the triage station today. Both can co-exist:
    //                  a nurse with Designation.TRIAGE_NURSE is a triage
    //                  specialist year-round, and the same nurse may or
    //                  may not be the ShiftFunction.TRIAGE_NURSE on any
    //                  given shift.
    //   SENIOR / STAFF / STUDENT — bedside seniority ladder.
    // A user picks one designation — a nurse cannot simultaneously hold
    // Charge Nurse and Triage Nurse; those are different jobs.
    CHARGE_NURSE("Charge Nurse", Role.NURSE),
    TRIAGE_NURSE("Triage Nurse", Role.NURSE),
    SENIOR_NURSE("Senior Nurse", Role.NURSE),
    STAFF_NURSE("Staff Nurse", Role.NURSE),
    STUDENT_NURSE("Student Nurse", Role.NURSE),

    // ── Lab Technician designations ──
    HEAD_LAB_TECHNICIAN("Head Lab Technician", Role.LAB_TECHNICIAN),
    LAB_TECHNICIAN("Lab Technician", Role.LAB_TECHNICIAN),

    // ── Registrar designations ──
    SENIOR_REGISTRAR("Senior Registrar", Role.REGISTRAR),
    REGISTRAR("Registrar", Role.REGISTRAR),

    // ── Paramedic designations ──
    SENIOR_PARAMEDIC("Senior Paramedic", Role.PARAMEDIC),
    PARAMEDIC("Paramedic", Role.PARAMEDIC),

    // ── General (fallback) ──
    UNSPECIFIED("Unspecified", null);

    private final String label;
    /** The Role this designation is typically associated with (null = any). */
    private final Role typicalRole;

    /**
     * Get all designations that are appropriate for a given system role.
     * Used by the admin UI to filter the designation dropdown.
     */
    public static Designation[] forRole(Role role) {
        if (role == null)
            return new Designation[] { UNSPECIFIED };
        return switch (role) {
            case DOCTOR ->
                new Designation[] { ED_HEAD, CONSULTANT, SENIOR_MEDICAL_OFFICER, MEDICAL_OFFICER, RESIDENT, INTERN };
            // Full nurse career ladder. CHARGE_NURSE = unit management,
            // TRIAGE_NURSE = intake/assessment specialist (year-round
            // assignment, separate from the per-shift ShiftFunction).
            // The remaining three are the bedside seniority ladder.
            case NURSE ->
                new Designation[] { CHARGE_NURSE, TRIAGE_NURSE, SENIOR_NURSE, STAFF_NURSE, STUDENT_NURSE };
            case LAB_TECHNICIAN -> new Designation[] { HEAD_LAB_TECHNICIAN, Designation.LAB_TECHNICIAN };
            case REGISTRAR -> new Designation[] { SENIOR_REGISTRAR, Designation.REGISTRAR };
            case PARAMEDIC -> new Designation[] { SENIOR_PARAMEDIC, Designation.PARAMEDIC };
            default -> new Designation[] { UNSPECIFIED };
        };
    }

    /**
     * Backend-enforced check: is the supplied (role, designation) pair
     * legal? Used by user-create/update services to reject invalid
     * combinations at persist time. The frontend dropdown filters via
     * {@link #forRole}, but UI filtering is not a security boundary —
     * an API client that bypasses the form (or a future code path that
     * forgets to filter) would otherwise be able to set, e.g., a
     * DOCTOR-role user to {@link #CHARGE_NURSE} and inherit nurse
     * unit-management authority.
     *
     * <p>{@code null} or {@link #UNSPECIFIED} are always valid. Any
     * other designation must be in {@link #forRole(Role)} for the
     * supplied role.
     */
    public static boolean isValidForRole(Designation designation, Role role) {
        if (designation == null || designation == UNSPECIFIED) return true;
        if (role == null) return false;
        for (Designation d : forRole(role)) {
            if (d == designation) return true;
        }
        return false;
    }
}
