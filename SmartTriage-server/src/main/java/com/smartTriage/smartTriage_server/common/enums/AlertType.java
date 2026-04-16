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

    // Lab turnaround alert types
    STAT_LAB_OVERDUE,
    URGENT_LAB_OVERDUE,
    CRITICAL_VALUE_UNACKNOWLEDGED,

    // Referral alert types
    REFERRAL_INITIATED,
    REFERRAL_STABILIZATION_INCOMPLETE,

    // System offline/online alert types
    SYSTEM_OFFLINE,
    SYSTEM_ONLINE,

    // Patient safety incident alert types
    SAFETY_INCIDENT_CRITICAL,

    // ICU escalation alert types
    ICU_ESCALATION_REQUESTED,
    ICU_BED_UNAVAILABLE
}
