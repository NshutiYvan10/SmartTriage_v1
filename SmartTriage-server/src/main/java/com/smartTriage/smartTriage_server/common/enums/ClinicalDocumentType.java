package com.smartTriage.smartTriage_server.common.enums;

/**
 * Types of formal clinical documents in the ED documentation system.
 * These are legally significant documents that require signing and
 * cannot be modified once signed (amendments only).
 */
public enum ClinicalDocumentType {
    INITIAL_ASSESSMENT,
    PROGRESS_NOTE,
    PROCEDURE_NOTE,
    CONSULTATION_NOTE,
    DISCHARGE_SUMMARY,
    TRANSFER_SUMMARY,
    HANDOVER_DOCUMENT,
    DEATH_CERTIFICATE,
    OPERATIVE_NOTE,
    NURSING_ASSESSMENT,
    TRIAGE_NARRATIVE,
    INFORMED_CONSENT,
    AGAINST_MEDICAL_ADVICE
}
