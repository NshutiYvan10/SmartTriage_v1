package com.smartTriage.smartTriage_server.common.enums;

import java.util.Arrays;
import java.util.List;

/**
 * Types of clinical alerts the system can generate.
 *
 * <p>Each type declares two pieces of routing metadata so behaviour can't drift:
 * <ul>
 *   <li>{@link AlertCategory category} — the urgency bucket the Alert Center uses to
 *       colour / filter / tab. Travels on the alert DTO, so a new type is categorised
 *       correctly in the UI the moment it is declared (no stale client-side copy).</li>
 *   <li>{@code timeCritical} — whether an UNACKNOWLEDGED instance must be re-paged
 *       (re-broadcast) by {@code AlertEscalationService} when nobody has acted on it.
 *       The re-escalation query derives its set from this flag via
 *       {@link #timeCriticalTypes()}, so a new time-critical type can never be silently
 *       dropped from the re-page loop by forgetting to add it to a hand-maintained list.</li>
 * </ul>
 *
 * <p>NB: {@link #EMS_PRE_ARRIVAL} is deliberately NOT timeCritical — it has its own
 * short-fuse re-escalation finder ({@code findUnescalatedCriticalEmsPreArrivals}); and
 * {@link #DOCTOR_NOTIFICATION}/{@link #DOCTOR_ESCALATION} have the tiered doctor
 * pipeline — they must not also be in the generic time-critical loop.
 */
public enum AlertType {
    TEWS_CRITICAL(AlertCategory.CLINICAL, false),
    TEWS_ESCALATION(AlertCategory.CLINICAL, false),
    VITAL_SIGN_ABNORMAL(AlertCategory.CLINICAL, false),
    RETRIAGE_REQUIRED(AlertCategory.CLINICAL, false),
    WAITING_TIME_EXCEEDED(AlertCategory.OPERATIONAL, false),
    DETERIORATION_DETECTED(AlertCategory.CLINICAL, true),
    SEPSIS_SCREENING(AlertCategory.CLINICAL, true),
    PEDIATRIC_SAFETY(AlertCategory.CLINICAL, false),
    REASSESSMENT_DUE(AlertCategory.OPERATIONAL, false),
    CRITICAL_LAB_RESULT(AlertCategory.CLINICAL, true),

    // IoT-specific alert types
    IOT_DEVICE_DISCONNECTED(AlertCategory.SYSTEM, false),
    IOT_DEVICE_LOW_BATTERY(AlertCategory.SYSTEM, false),
    IOT_SIGNAL_QUALITY_DEGRADED(AlertCategory.SYSTEM, false),
    IOT_AUTO_RETRIAGE(AlertCategory.CLINICAL, false),
    DOCTOR_NOTIFICATION(AlertCategory.CLINICAL, false),
    DOCTOR_ESCALATION(AlertCategory.CLINICAL, false),
    SURGE_WARNING(AlertCategory.SYSTEM, false),
    INVESTIGATION_RESULTED(AlertCategory.OPERATIONAL, false),

    // Medication safety alert types
    MEDICATION_SAFETY_BLOCK(AlertCategory.CLINICAL, false),
    MEDICATION_SAFETY_WARNING(AlertCategory.OPERATIONAL, false),
    /**
     * STAT medication has been sitting in PRESCRIBED status past the
     * 10-minute administration SLA. Severity CRITICAL — STAT means
     * "give immediately" and a missed STAT is patient-safety harm.
     */
    STAT_MEDICATION_OVERDUE(AlertCategory.CLINICAL, true),
    /**
     * URGENT medication has been sitting in PRESCRIBED status past
     * the 30-minute SLA. Severity HIGH — less time-critical than
     * STAT but still flags missed care.
     */
    URGENT_MEDICATION_OVERDUE(AlertCategory.CLINICAL, false),

    // Medication Management (V67) — dose-level workflow
    /**
     * A scheduled dose is past its due time + grace window but has
     * not been given, refused, or held. Severity HIGH; re-notifies
     * the zone so the nurse can act before it becomes MISSED.
     */
    MEDICATION_DOSE_OVERDUE(AlertCategory.CLINICAL, false),
    /**
     * A scheduled dose was never administered within the missed
     * threshold. Severity CRITICAL; escalated to the charge nurse —
     * a missed antibiotic/anticonvulsant dose is direct patient harm.
     */
    MEDICATION_DOSE_MISSED(AlertCategory.CLINICAL, true),
    /**
     * A high-alert medication order is awaiting charge-nurse approval
     * before it can be administered (V67 approval gate).
     */
    MEDICATION_APPROVAL_REQUIRED(AlertCategory.OPERATIONAL, false),
    /**
     * A clinician used the emergency override path — skipping the
     * approval gate or a failed PRN vitals gate — with documented
     * justification. Department-visible by design.
     */
    MEDICATION_EMERGENCY_OVERRIDE(AlertCategory.CLINICAL, false),

    // Lab turnaround alert types
    STAT_LAB_OVERDUE(AlertCategory.CLINICAL, true),
    URGENT_LAB_OVERDUE(AlertCategory.OPERATIONAL, false),
    ROUTINE_LAB_OVERDUE(AlertCategory.OPERATIONAL, false),
    CRITICAL_VALUE_UNACKNOWLEDGED(AlertCategory.CLINICAL, true),
    /**
     * Early-warning alert: a lab order has been sitting in ORDERED
     * status (specimen not yet received by the lab) past one-third of
     * its priority's total SLA. Fires before STAT_LAB_OVERDUE /
     * URGENT_LAB_OVERDUE would so the lab tech / runner has a chance
     * to act before the total turnaround SLA is breached.
     */
    LAB_NOT_RECEIVED(AlertCategory.OPERATIONAL, false),
    /**
     * A lab specimen was rejected on receipt (haemolysed, clotted,
     * mislabelled, etc.) and the ordering doctor must redraw. Previously
     * this re-used CRITICAL_LAB_RESULT, which mis-categorised redraw
     * notices as critical results and would have dragged them into the
     * time-critical re-escalation set; it now has its own type.
     */
    LAB_SPECIMEN_REJECTED(AlertCategory.OPERATIONAL, false),
    /**
     * A junior tech released an AWAITING_VERIFICATION result WITHOUT senior
     * sign-off (emergency override) — a safety-gate bypass that governance /
     * the senior on shift must see. Previously re-used CRITICAL_LAB_RESULT.
     * NOT timeCritical: it is a governance/audit notice delivered to the senior +
     * charge nurse + zone doctor at creation, and is raised at HIGH severity for a
     * non-critical result — re-paging it hospital-wide as CRITICAL after 5 min would be
     * false-alarm fatigue. A CRITICAL underlying result is already covered by its own
     * CRITICAL_LAB_RESULT / CRITICAL_VALUE_UNACKNOWLEDGED re-escalation.
     */
    LAB_VERIFICATION_OVERRIDDEN(AlertCategory.CLINICAL, false),

    // EMS / paramedic workflow
    EMS_PRE_ARRIVAL(AlertCategory.CLINICAL, false),                       // Ambulance is en route — bay prep (own short-fuse loop)
    EMS_HANDOVER_PENDING(AlertCategory.CLINICAL, false),                  // Patient at door, no ED ack yet
    FIELD_TRIAGED_AWAITING_REVIEW(AlertCategory.CLINICAL, false),         // 15 min elapsed, ED nurse hasn't re-triaged

    // System offline/online alert types
    SYSTEM_OFFLINE(AlertCategory.SYSTEM, false),
    SYSTEM_ONLINE(AlertCategory.SYSTEM, false),

    // Patient safety incident alert types
    SAFETY_INCIDENT_CRITICAL(AlertCategory.CLINICAL, true),

    // ICU escalation alert types
    ICU_ESCALATION_REQUESTED(AlertCategory.CLINICAL, true),
    ICU_BED_UNAVAILABLE(AlertCategory.CLINICAL, true),

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
    DIRECT_RESUS_ADMISSION(AlertCategory.CLINICAL, true),
    RESUS_OVERFLOW(AlertCategory.CLINICAL, true),
    IDENTITY_UNRESOLVED(AlertCategory.OPERATIONAL, false),

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
    BED_AVAILABLE(AlertCategory.OPERATIONAL, false),

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
    SEPSIS_BUNDLE_NOT_STARTED(AlertCategory.CLINICAL, true),
    SEPSIS_BUNDLE_OVERDUE(AlertCategory.CLINICAL, true),

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
    FAST_TRACK_ACTIVATED(AlertCategory.CLINICAL, true),
    FAST_TRACK_SLA_BREACH(AlertCategory.CLINICAL, true),

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
    HYPOGLYCEMIA_CRITICAL(AlertCategory.CLINICAL, true),
    HYPOGLYCEMIA_RECHECK_OVERDUE(AlertCategory.CLINICAL, true),

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
    ISOLATION_REQUIRED(AlertCategory.CLINICAL, true),
    ISOLATION_PLACEMENT_OVERDUE(AlertCategory.CLINICAL, true),
    NOTIFIABLE_DISEASE(AlertCategory.CLINICAL, true),

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
    PATHWAY_ACTIVATED(AlertCategory.CLINICAL, true),
    PATHWAY_STEP_OVERDUE(AlertCategory.CLINICAL, true);

    private final AlertCategory category;
    private final boolean timeCritical;

    AlertType(AlertCategory category, boolean timeCritical) {
        this.category = category;
        this.timeCritical = timeCritical;
    }

    public AlertCategory getCategory() {
        return category;
    }

    /**
     * True when an UNACKNOWLEDGED instance of this type must be re-paged by the
     * escalation scheduler if nobody acts on it. Drives {@link #timeCriticalTypes()}.
     */
    public boolean isTimeCritical() {
        return timeCritical;
    }

    /** The set of types the re-escalation loop must scan — derived from the flag above,
     *  so a new time-critical type is never silently omitted. Immutable, computed once. */
    private static final List<AlertType> TIME_CRITICAL_TYPES =
            Arrays.stream(values()).filter(AlertType::isTimeCritical).toList();

    public static List<AlertType> timeCriticalTypes() {
        return TIME_CRITICAL_TYPES;
    }
}
