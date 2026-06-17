package com.smartTriage.smartTriage_server.common.enums;

/**
 * Coarse classification of an {@link AlertType} by clinical urgency, so the
 * Alert Center can colour / filter / tab alerts in a way that matches how a
 * clinician must respond — without re-deriving the grouping on the client from
 * a copy of the enum that inevitably drifts behind the backend.
 *
 * <p>This is the SINGLE SOURCE OF TRUTH for categorisation: it travels on the
 * alert DTO so a new {@link AlertType} is categorised correctly everywhere the
 * moment it is declared, instead of silently defaulting to "routine" in the UI.
 *
 *   CLINICAL    — life-threatening or patient-deterioration events the clinician
 *                 must look at NOW (TEWS critical, sepsis, deterioration, critical
 *                 lab, hypoglycemia, isolation, fast-track, pathway activation,
 *                 resus, ICU escalation, missed/STAT meds, doctor notifications).
 *   OPERATIONAL — workflow / care-coordination events: important but not
 *                 immediately life-threatening (non-critical results, lab/dose
 *                 overdue, waiting-time, reassessment, bed availability, identity).
 *   SYSTEM      — system-state / device events (IoT disconnect, low battery,
 *                 signal degraded, system online/offline, surge warning).
 */
public enum AlertCategory {
    CLINICAL,
    OPERATIONAL,
    SYSTEM
}
