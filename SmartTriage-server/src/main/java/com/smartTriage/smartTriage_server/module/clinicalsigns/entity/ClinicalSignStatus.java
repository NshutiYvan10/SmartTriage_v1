package com.smartTriage.smartTriage_server.module.clinicalsigns.entity;

/**
 * Status of a clinical sign at a point in time.
 *
 * Five-state by design — clinical reality often is "still there but
 * getting better" or "can't assess right now". UNKNOWN is critical:
 * it tells the re-triage engine "we couldn't check" rather than silently
 * equating to ABSENT, which would let the engine think a sign resolved
 * when really we just stopped looking.
 *
 * Semantics:
 *   PRESENT    — sign is positive (newly observed or persisting)
 *   ABSENT     — sign is negative (resolved, or never present)
 *   IMPROVING  — still present but trending favorably
 *   WORSENING  — still present but trending unfavorably
 *   UNKNOWN    — clinician could not assess (sedated, intubated, etc.).
 *                Must NOT be silently treated as ABSENT by any consumer.
 */
public enum ClinicalSignStatus {
    PRESENT,
    ABSENT,
    IMPROVING,
    WORSENING,
    UNKNOWN
}
