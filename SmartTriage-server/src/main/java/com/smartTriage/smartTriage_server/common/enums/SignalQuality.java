package com.smartTriage.smartTriage_server.common.enums;

/**
 * Quality assessment of an IoT vital reading.
 * Used by the noise-filtering and validation engine.
 */
public enum SignalQuality {

    /** High-confidence reading — sensor stable, no artefacts */
    GOOD,

    /** Acceptable reading — minor noise but clinically usable */
    ACCEPTABLE,

    /** Low-quality reading — noise detected, use with caution */
    POOR,

    /** Invalid reading — sensor disconnected, artefact, or out-of-range */
    INVALID,

    /** Quality not assessed (e.g., manual override) */
    UNKNOWN
}
