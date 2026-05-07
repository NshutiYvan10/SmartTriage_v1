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
    // Every nurse holds Role.NURSE. The designation captures the function
    // she performs on the unit. A nurse can only hold ONE designation at
    // a time, so the system naturally prevents impossible combinations
    // (e.g. a single nurse cannot simultaneously be Charge Nurse AND
    // Triage Nurse — those are different jobs on the same shift).
    //
    // V29: TRIAGE_NURSE was previously a top-level Role; it lives here now.
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
            // TRIAGE_NURSE = intake/assessment specialist, the others =
            // seniority on the bedside ladder. A user picks one — a single
            // nurse cannot simultaneously be Charge Nurse and Triage Nurse.
            case NURSE ->
                new Designation[] { CHARGE_NURSE, TRIAGE_NURSE, SENIOR_NURSE, STAFF_NURSE, STUDENT_NURSE };
            case LAB_TECHNICIAN -> new Designation[] { HEAD_LAB_TECHNICIAN, Designation.LAB_TECHNICIAN };
            case REGISTRAR -> new Designation[] { SENIOR_REGISTRAR, Designation.REGISTRAR };
            case PARAMEDIC -> new Designation[] { SENIOR_PARAMEDIC, Designation.PARAMEDIC };
            default -> new Designation[] { UNSPECIFIED };
        };
    }
}
