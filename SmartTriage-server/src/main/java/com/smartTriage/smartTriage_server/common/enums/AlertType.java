package com.smartTriage.smartTriage_server.common.enums;

/**
 * Types of clinical alerts the system can generate.
 */
public enum AlertType {
    TEWS_CRITICAL,
    TEWS_ESCALATION,
    VITAL_SIGN_ABNORMAL,
    RETRIAGE_REQUIRED,
    WAITING_TIME_EXCEEDED,
    DETERIORATION_DETECTED,
    SEPSIS_SCREENING,
    PEDIATRIC_SAFETY,
    REASSESSMENT_DUE,
    CRITICAL_LAB_RESULT,

    // IoT-specific alert types
    IOT_DEVICE_DISCONNECTED,
    IOT_DEVICE_LOW_BATTERY,
    IOT_SIGNAL_QUALITY_DEGRADED,
    IOT_AUTO_RETRIAGE,
    DOCTOR_NOTIFICATION,
    DOCTOR_ESCALATION,
    SURGE_WARNING,
    INVESTIGATION_RESULTED,

    // Medication safety alert types
    MEDICATION_SAFETY_BLOCK,
    MEDICATION_SAFETY_WARNING,
    /**
     * STAT medication has been sitting in PRESCRIBED status past the
     * 10-minute administration SLA. Severity CRITICAL — STAT means
     * "give immediately" and a missed STAT is patient-safety harm.
     */
    STAT_MEDICATION_OVERDUE,
    /**
     * URGENT medication has been sitting in PRESCRIBED status past
     * the 30-minute SLA. Severity HIGH — less time-critical than
     * STAT but still flags missed care.
     */
    URGENT_MEDICATION_OVERDUE,

    // Medication Management (V67) — dose-level workflow
    /**
     * A scheduled dose is past its due time + grace window but has
     * not been given, refused, or held. Severity HIGH; re-notifies
     * the zone so the nurse can act before it becomes MISSED.
     */
    MEDICATION_DOSE_OVERDUE,
    /**
     * A scheduled dose was never administered within the missed
     * threshold. Severity CRITICAL; escalated to the charge nurse —
     * a missed antibiotic/anticonvulsant dose is direct patient harm.
     */
    MEDICATION_DOSE_MISSED,
    /**
     * A high-alert medication order is awaiting charge-nurse approval
     * before it can be administered (V67 approval gate).
     */
    MEDICATION_APPROVAL_REQUIRED,
    /**
     * A clinician used the emergency override path — skipping the
     * approval gate or a failed PRN vitals gate — with documented
     * justification. Department-visible by design.
     */
    MEDICATION_EMERGENCY_OVERRIDE,

    // Lab turnaround alert types
    STAT_LAB_OVERDUE,
    URGENT_LAB_OVERDUE,
    CRITICAL_VALUE_UNACKNOWLEDGED,
    /**
     * Early-warning alert: a lab order has been sitting in ORDERED
     * status (specimen not yet received by the lab) past one-third of
     * its priority's total SLA. Fires before STAT_LAB_OVERDUE /
     * URGENT_LAB_OVERDUE would so the lab tech / runner has a chance
     * to act before the total turnaround SLA is breached.
     */
    LAB_NOT_RECEIVED,

    // EMS / paramedic workflow
    EMS_PRE_ARRIVAL,                       // Ambulance is en route — bay prep
    EMS_HANDOVER_PENDING,                  // Patient at door, no ED ack yet
    FIELD_TRIAGED_AWAITING_REVIEW,         // 15 min elapsed, ED nurse hasn't re-triaged

    // System offline/online alert types
    SYSTEM_OFFLINE,
    SYSTEM_ONLINE,

    // Patient safety incident alert types
    SAFETY_INCIDENT_CRITICAL,

    // ICU escalation alert types
    ICU_ESCALATION_REQUESTED,
    ICU_BED_UNAVAILABLE,

    // Direct Resus Admission alert types (V44)
    // - DIRECT_RESUS_ADMISSION:  CRITICAL alert fanned to the resus zone
    //                            the moment a nurse declares Direct Resus.
    // - RESUS_OVERFLOW:          CRITICAL alert when the new admission has
    //                            no available RESUS bed; carries the
    //                            transfer-candidate ranking in the message.
    // - IDENTITY_UNRESOLVED:     HIGH alert raised by the scheduled job
    //                            when an unidentified patient has been in
    //                            the system >= 2h without identity being
    //                            resolved. Targets the charge nurse.
    DIRECT_RESUS_ADMISSION,
    RESUS_OVERFLOW,
    IDENTITY_UNRESOLVED,

    /**
     * High-acuity zone bed transitioned to AVAILABLE — triggered when a
     * bed in RESUS / ACUTE / PEDIATRIC / NEONATAL becomes free (cleaning
     * complete or returned from out-of-service). Lets the on-duty
     * charge nurse / shift lead know capacity has been restored so an
     * overflow patient can be transferred in or a pending admission
     * can advance. We deliberately do NOT alert for low-acuity zones
     * (GENERAL, AMBULATORY, OBSERVATION) where the operational tempo
     * doesn't warrant a notification.
     */
    BED_AVAILABLE,

    /**
     * Sepsis 1-hour bundle compliance escalations. DISTINCT from the
     * SEPSIS_SCREENING detection alert on purpose: the bundle monitor dedups
     * on these types, so an unacknowledged original SEPSIS_SCREENING alert can
     * no longer suppress the escalation for a patient nobody has acted on.
     *  - SEPSIS_BUNDLE_NOT_STARTED: sepsis detected but the 1-hour bundle was
     *    not started within the start deadline.
     *  - SEPSIS_BUNDLE_OVERDUE:     bundle in progress past the 60-minute
     *    completion deadline.
     */
    SEPSIS_BUNDLE_NOT_STARTED,
    SEPSIS_BUNDLE_OVERDUE,

    /**
     * Fast-track (time-critical stroke / STEMI / NSTEMI / TIA) pathway alerts.
     *  - FAST_TRACK_ACTIVATED: a stroke/MI pathway was activated for a visit;
     *    CRITICAL, owned by the zone doctor + charge nurse (door-to-treatment
     *    clock is now running). A dedicated type (not VITAL_SIGN_ABNORMAL) so it
     *    is distinguishable in the alert stream AND picked up by the
     *    time-critical re-escalation loop if left unacknowledged.
     *  - FAST_TRACK_SLA_BREACH: a door-to-ECG / door-to-CT / door-to-needle
     *    target was missed on an active pathway — raised by the fast-track
     *    monitor and re-paged to the accountable clinicians.
     */
    FAST_TRACK_ACTIVATED,
    FAST_TRACK_SLA_BREACH,

    /**
     * Hypoglycemia detection + recheck escalation (fatal in minutes).
     *  - HYPOGLYCEMIA_CRITICAL: a low glucose reading was detected. CRITICAL for
     *    moderate/severe (and HIGH for mild), owned by the zone doctor + nurse +
     *    charge nurse and pushed in real time. A dedicated type (not
     *    VITAL_SIGN_ABNORMAL) so it is distinguishable AND picked up by the
     *    time-critical re-escalation loop.
     *  - HYPOGLYCEMIA_RECHECK_OVERDUE: the mandatory 15-minute post-treatment
     *    recheck was not performed — raised by the recheck monitor.
     */
    HYPOGLYCEMIA_CRITICAL,
    HYPOGLYCEMIA_RECHECK_OVERDUE,

    /**
     * Infection isolation (staff + patient exposure control).
     *  - ISOLATION_REQUIRED: a screening flagged an isolation need (airborne /
     *    droplet / contact / strict / protective). Owned by the zone doctor +
     *    charge nurse (for bed/zone reassignment) and pushed in real time. A
     *    dedicated type (not VITAL_SIGN_ABNORMAL) so it is distinguishable AND
     *    picked up by the time-critical re-escalation loop.
     *  - ISOLATION_PLACEMENT_OVERDUE: a flagged patient was not moved into an
     *    isolation room within the placement window — raised by the placement monitor.
     *  - NOTIFIABLE_DISEASE: a Rwanda-IDSR notifiable disease was suspected;
     *    Rwanda Biomedical Centre (RBC) must be notified within 24 hours.
     */
    ISOLATION_REQUIRED,
    ISOLATION_PLACEMENT_OVERDUE,
    NOTIFIABLE_DISEASE,

    /**
     * Clinical-pathway protocol execution.
     *  - PATHWAY_ACTIVATED: a time-critical care pathway (e.g. status epilepticus,
     *    severe malaria, obstetric emergency) was activated for a visit — owned by
     *    the zone doctor + charge nurse and pushed in real time for coordination.
     *  - PATHWAY_STEP_OVERDUE: a mandatory pathway step passed its protocol timeframe
     *    without being done/skipped — raised by the pathway compliance monitor.
     * Dedicated types (not REASSESSMENT_DUE) so they are distinguishable AND picked
     * up by the time-critical re-escalation loop.
     */
    PATHWAY_ACTIVATED,
    PATHWAY_STEP_OVERDUE
}
