package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * MonitoringState — clinical-facing lifecycle of a single
 * {@code DeviceSession}. Distinct from {@code DeviceStatus} (which
 * describes the hardware) and {@code BedStatus} (which describes the
 * bed). This state answers the question a clinician actually asks:
 * "is this patient being monitored right now, and if not, why not?"
 *
 * <p>State transitions (full table):
 * <pre>
 * NOT_STARTED   → STARTING        clinician pressed Start
 * STARTING      → LIVE            first validated reading arrived
 * STARTING      → STALLED         30s elapsed with no validated reading
 * LIVE          → DEGRADED        signal poor &ge; 60s OR &ge;30% rejects/60s
 * LIVE          → STALLED         no validated reading in last 30s
 * LIVE/DEGRADED/STALLED → DISCONNECTED   device heartbeat lost
 * DEGRADED      → LIVE            signal recovers
 * STALLED       → LIVE            reading arrives
 * DISCONNECTED  → STARTING        device heartbeat returns (probes may
 *                                 have moved — re-grace before LIVE)
 * any non-terminal → PAUSED       clinician pressed Pause
 * PAUSED        → STARTING        clinician pressed Resume
 * any non-terminal → ENDED        clinician pressed End, or patient
 *                                 was discharged / transferred
 * </pre>
 *
 * <p>{@link #ENDED} is terminal — historical sessions stay ENDED and a
 * new session is opened if monitoring is restarted later.
 *
 * <p>Severity ordering for UI sorting / alert routing:
 * higher integer = more clinical attention needed.
 */
@Getter
@RequiredArgsConstructor
public enum MonitoringState {

    /** Bed is occupied but no clinician has started monitoring yet. */
    NOT_STARTED("Awaiting Start", 0),

    /**
     * Clinician started; waiting for the first validated reading.
     * Auto-retriage and deterioration alerts are intentionally
     * suppressed in this state (warm-up window).
     */
    STARTING("Connecting…", 1),

    /** Validated readings flowing with acceptable signal quality. */
    LIVE("Live", 2),

    /**
     * Readings still arriving but signal is consistently poor or a
     * meaningful fraction are being rejected. Clinician should check
     * sensor placement; auto-retriage continues.
     */
    DEGRADED("Signal poor", 3),

    /**
     * Device is online but no validated reading has arrived in the
     * stall window. Typically: probe disconnected from the patient,
     * sensor artifact, or every recent packet failed validation.
     */
    STALLED("No data", 4),

    /**
     * Clinician paused monitoring (patient at imaging / procedure).
     * Vitals are not displayed and deterioration alerts are
     * suppressed. Session must be explicitly resumed.
     */
    PAUSED("Paused", 1),

    /**
     * Device lost its heartbeat. Session is suspended (not ENDED) so
     * the clinical timeline keeps a single record when the device
     * reconnects. Fires {@code IOT_DEVICE_DISCONNECTED}.
     */
    DISCONNECTED("Device offline", 5),

    /** Terminal. Session is closed. */
    ENDED("Ended", 0);

    private final String label;
    private final int severity;

    /** True for the states where vitals should be ingested + processed. */
    public boolean acceptsReadings() {
        return this == STARTING || this == LIVE
                || this == DEGRADED || this == STALLED;
    }

    /** True for the states where auto-retriage / deterioration alerts should fire. */
    public boolean allowsAutoRetriage() {
        return this == LIVE || this == DEGRADED;
    }

    /** True when this is a terminal state. */
    public boolean isTerminal() {
        return this == ENDED;
    }
}
