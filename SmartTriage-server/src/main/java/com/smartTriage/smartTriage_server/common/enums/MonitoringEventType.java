package com.smartTriage.smartTriage_server.common.enums;

/**
 * MonitoringEventType — what kind of transition a monitoring_events row
 * records (V119). Transitions only, never per-reading samples.
 */
public enum MonitoringEventType {
    /** A deterioration pattern appeared, or changed to a different pattern. */
    PATTERN_DETECTED,
    /** The live detection annotation cleared (patient no longer worsening). */
    PATTERN_CLEARED,
    /** The hysteresis-confirmed trend label changed (e.g. STABLE → WORSENING). */
    TREND_CHANGED,
    /** The engine escalated the triage category (system-triggered retriage). */
    AUTO_RETRIAGE,
    /** Monitoring session lifecycle. */
    SESSION_STARTED,
    SESSION_PAUSED,
    SESSION_RESUMED,
    SESSION_ENDED
}
