package com.smartTriage.smartTriage_server.common.enums;

/**
 * System roles following hospital RBAC structure.
 *
 * <p>SmartTriage is an emergency-department system; the role list is
 * scoped to clinical and administrative staff that operate inside an
 * ED.
 *
 * <p>Roles are *system access categories*, not clinical specialties.
 * A nurse assigned to the triage station for a shift is still a
 * {@link #NURSE} — that assignment lives in
 * {@code ShiftAssignment.shiftFunction = TRIAGE_NURSE}. Likewise a
 * charge nurse is a {@link #NURSE} with
 * {@code Designation.CHARGE_NURSE}, not a separate role. Keeping role,
 * designation, and shift function as three distinct concepts prevents
 * the operational mess of admins editing role on every rotation.
 *
 * <p>Notable removals (V39): {@code TRIAGE_NURSE} was previously a
 * role; it was merged into {@link #NURSE}. PHARMACIST and MIDWIFE were
 * never roles in this system and remain out of scope (not relevant to
 * ED operations).
 */
public enum Role {
    SUPER_ADMIN,
    HOSPITAL_ADMIN,
    DOCTOR,
    NURSE,
    REGISTRAR,
    PARAMEDIC,
    LAB_TECHNICIAN,
    READ_ONLY
}
