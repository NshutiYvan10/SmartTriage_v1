package com.smartTriage.smartTriage_server.common.enums;

/**
 * Types of fast-track activations for time-critical conditions.
 * Stroke and MI require immediate protocol activation to minimize door-to-treatment times.
 */
public enum FastTrackType {
    STROKE_SUSPECTED,
    STEMI_SUSPECTED,
    NSTEMI_SUSPECTED,
    TIA_SUSPECTED
}
