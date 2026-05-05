package com.smartTriage.smartTriage_server.common.enums;

/**
 * Patient trend classification derived from continuous vital-sign monitoring.
 * Computed server-side with hysteresis so every client renders the same label.
 */
public enum TrendStatus {
    /** Vitals moving toward danger or any current reading in the RED band. */
    WORSENING,

    /** Vitals flat within normal/acceptable range, no significant drift. */
    STABLE,

    /** Vitals consistently moving back toward normal from a prior abnormal state. */
    IMPROVING,

    /** Insufficient data (too few readings) to classify yet. */
    UNKNOWN
}
