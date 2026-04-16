package com.smartTriage.smartTriage_server.common.enums;

/**
 * Status progression for fast-track activations.
 * Tracks the workflow from activation through investigation to intervention/completion.
 */
public enum FastTrackStatus {
    ACTIVATED,
    ECG_ORDERED,
    ECG_COMPLETED,
    CT_ORDERED,
    CT_COMPLETED,
    THROMBOLYSIS_CONSIDERED,
    INTERVENTION_STARTED,
    TRANSFERRED_FOR_PCI,
    COMPLETED,
    CANCELLED
}
