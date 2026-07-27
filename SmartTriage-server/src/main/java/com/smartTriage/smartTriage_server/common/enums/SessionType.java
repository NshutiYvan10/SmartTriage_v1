package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Kind of device↔visit monitoring session.
 *
 * <p>{@link #CONTINUOUS} — the classic bed-monitor session: open-ended,
 * ends when a clinician stops it, the patient is transferred, or the
 * visit closes.
 *
 * <p>{@link #SPOT_CHECK} — the roaming obs-round session for chair-based
 * zones (GENERAL/AMBULATORY): a nurse wheels a shared monitor to the
 * patient, the session opens, and it SELF-COMPLETES once one validated
 * full vitals set (≥2 validated readings including HR, SpO2 and systolic
 * BP) has been captured — producing a clinical VitalSigns snapshot that
 * resets the patient's reassessment clock. A 10-minute hard timeout
 * closes forgotten spot-checks as incomplete so the shared monitor is
 * never logically glued to a patient who walked off.
 */
@Getter
@RequiredArgsConstructor
public enum SessionType {
    CONTINUOUS("Continuous monitoring"),
    SPOT_CHECK("Spot check");

    private final String label;
}
