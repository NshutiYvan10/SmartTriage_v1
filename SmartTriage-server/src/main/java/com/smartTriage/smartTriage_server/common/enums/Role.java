package com.smartTriage.smartTriage_server.common.enums;

/**
 * System roles following hospital RBAC structure.
 *
 * <p><b>V29 restructure:</b> {@code TRIAGE_NURSE} was previously a top-level
 * Role here. It has been moved to the {@link Designation} enum
 * ({@code Designation.TRIAGE_NURSE}, typical role {@code NURSE}) because
 * a triage nurse is still a nurse — "triage" describes the function she
 * performs on the unit, not a separate profession. See
 * {@code V29__triage_nurse_role_to_designation.sql} for the data
 * migration that re-points existing users.
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
