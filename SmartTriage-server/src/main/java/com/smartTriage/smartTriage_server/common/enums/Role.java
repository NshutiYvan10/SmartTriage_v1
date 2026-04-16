package com.smartTriage.smartTriage_server.common.enums;

/**
 * System roles following hospital RBAC structure.
 */
public enum Role {
    SUPER_ADMIN,
    HOSPITAL_ADMIN,
    DOCTOR,
    TRIAGE_NURSE,
    NURSE,
    REGISTRAR,
    PARAMEDIC,
    LAB_TECHNICIAN,
    READ_ONLY
}
