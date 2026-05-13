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
    BED_AVAILABLE
}
