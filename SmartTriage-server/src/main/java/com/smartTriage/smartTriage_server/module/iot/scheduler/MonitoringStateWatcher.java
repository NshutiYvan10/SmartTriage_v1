package com.smartTriage.smartTriage_server.module.iot.scheduler;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.MonitoringState;
import com.smartTriage.smartTriage_server.common.enums.SignalQuality;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.entity.VitalStream;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.VitalStreamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * MonitoringStateWatcher — periodic background task that keeps each
 * non-terminal DeviceSession's {@code monitoringState} in sync with
 * what the device + readings are actually doing.
 *
 * <p>Runs every 10 seconds. For each open, non-PAUSED session it
 * applies the transition rules defined on {@link MonitoringState}:
 * <ul>
 *   <li>Device OFFLINE → state becomes DISCONNECTED, fires
 *       {@code IOT_DEVICE_DISCONNECTED} (severity HIGH). Session is
 *       NOT closed — when the device reconnects the heartbeat path
 *       transitions back to STARTING and the clinical timeline stays
 *       one continuous record.</li>
 *   <li>Last validated reading older than 30s → STALLED (with the
 *       same IOT_DEVICE_DISCONNECTED alert when sustained ≥90s).</li>
 *   <li>Sustained POOR/INVALID signal or ≥30% rejection rate over the
 *       last 60s → DEGRADED, fires {@code IOT_SIGNAL_QUALITY_DEGRADED}
 *       (severity MEDIUM). Auto-retriage continues — clinician
 *       decides if a probe re-seat is required.</li>
 *   <li>Battery &lt; 20% → fires {@code IOT_DEVICE_LOW_BATTERY}
 *       (severity MEDIUM), independent of state.</li>
 * </ul>
 *
 * <p>Recovery transitions (back to LIVE) happen on the synchronous
 * ingest path in {@code VitalStreamService.ingestVitals} — a freshly
 * arriving validated reading is the authoritative signal that we're
 * live again. The watcher only handles the "things have stopped"
 * direction.
 *
 * <p>All alerts are deduplicated against an unacknowledged open
 * alert of the same type on the same visit. The clinician's ack closes
 * the loop; a fresh disturbance re-raises.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringStateWatcher {

    private final DeviceSessionRepository sessionRepository;
    private final VitalStreamRepository streamRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;

    /** No-reading window before a session goes STALLED. */
    private static final int STALL_AFTER_SECONDS = 30;
    /** STALLED window beyond which we also raise IOT_DEVICE_DISCONNECTED. */
    private static final int STALL_ALERT_AFTER_SECONDS = 90;
    /** Sliding window for signal-quality and rejection-rate evaluation. */
    private static final int SIGNAL_WINDOW_SECONDS = 60;
    /** Reject ratio that triggers DEGRADED. */
    private static final double DEGRADE_REJECT_RATIO = 0.30;
    /** Minimum readings in the window to evaluate ratios — avoids
     *  false positives on a session that's only sent 1–2 packets. */
    private static final int SIGNAL_MIN_SAMPLES = 5;
    /** Battery percentage below which the low-battery alert fires. */
    private static final int LOW_BATTERY_PERCENT = 20;

    @Scheduled(fixedDelayString = "${smarttriage.iot.monitoring-watch-interval-ms:10000}")
    @Transactional
    public void tick() {
        List<DeviceSession> openSessions = sessionRepository.findBySessionActiveTrueAndIsActiveTrue();
        for (DeviceSession session : openSessions) {
            try {
                evaluate(session);
            } catch (Exception e) {
                log.warn("MonitoringStateWatcher: error evaluating session {}: {}",
                        session.getId(), e.getMessage());
            }
        }
    }

    private void evaluate(DeviceSession session) {
        MonitoringState state = session.getMonitoringState();
        if (state == null) return;
        // PAUSED + ENDED are intentionally not touched here — only the
        // clinician (or end-of-session path) moves out of these.
        if (state == MonitoringState.PAUSED || state == MonitoringState.ENDED) {
            return;
        }

        IoTDevice device = session.getDevice();
        if (device == null) return;

        // Battery alert — independent of state transitions.
        maybeRaiseBatteryAlert(session, device);

        // Device OFFLINE / DECOMMISSIONED → DISCONNECTED.
        if (device.getStatus() == DeviceStatus.OFFLINE
                || device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            if (state != MonitoringState.DISCONNECTED) {
                transition(session, MonitoringState.DISCONNECTED);
                raiseAlert(session, AlertType.IOT_DEVICE_DISCONNECTED, AlertSeverity.HIGH,
                        "Monitor offline — patient unmonitored",
                        deviceLostMessage(session));
            }
            return;
        }

        // Last validated reading age.
        //
        // Reference time = the LATER of (last validated reading's
        // capturedAt) and (the session's most recent transition into
        // a streaming state). Without the state-transition floor, a
        // freshly-resumed session can be tripped to STALLED before
        // the kickstart reading is ingested: the most recent
        // VitalStream on the row is from BEFORE the pause (could be
        // arbitrarily old), so the 30s threshold is already crossed
        // the instant the watcher tick runs.
        //
        // Using monitoringStateAt as a floor means a fresh
        // STARTING (whether from Start or from Resume) gets the same
        // 30-second grace window as a brand-new session, and STALL
        // only fires after 30 seconds without ANY new reading
        // following the transition.
        Optional<VitalStream> last = streamRepository
                .findFirstBySessionIdAndIsValidatedTrueAndIsActiveTrueOrderByCapturedAtDesc(
                        session.getId());
        Instant now = Instant.now();
        Instant stateAt = session.getMonitoringStateAt() != null
                ? session.getMonitoringStateAt()
                : session.getStartedAt();
        Instant reference;
        if (last.isPresent()) {
            Instant lastReadingAt = last.get().getCapturedAt();
            reference = lastReadingAt.isAfter(stateAt) ? lastReadingAt : stateAt;
        } else {
            reference = stateAt;
        }
        long secondsSinceLastReading = java.time.Duration.between(reference, now).getSeconds();

        if (secondsSinceLastReading >= STALL_AFTER_SECONDS) {
            if (state != MonitoringState.STALLED) {
                transition(session, MonitoringState.STALLED);
            }
            if (secondsSinceLastReading >= STALL_ALERT_AFTER_SECONDS) {
                raiseAlert(session, AlertType.IOT_DEVICE_DISCONNECTED, AlertSeverity.HIGH,
                        "Monitor stalled — no readings",
                        "No validated readings from monitor "
                                + device.getSerialNumber() + " for "
                                + secondsSinceLastReading + "s. Check probe placement.");
            }
            return;
        }

        // Signal quality / rejection rate over the sliding window.
        Instant windowStart = now.minusSeconds(SIGNAL_WINDOW_SECONDS);
        long totalInWindow = streamRepository
                .countBySessionIdAndIsActiveTrueAndCapturedAtAfter(session.getId(), windowStart);
        if (totalInWindow >= SIGNAL_MIN_SAMPLES) {
            long rejected = streamRepository
                    .countBySessionIdAndIsValidatedFalseAndIsActiveTrueAndCapturedAtAfter(
                            session.getId(), windowStart);
            long poorOrInvalid = streamRepository
                    .countBySessionIdAndIsActiveTrueAndSignalQualityInAndCapturedAtAfter(
                            session.getId(),
                            Arrays.asList(SignalQuality.POOR, SignalQuality.INVALID),
                            windowStart);
            double rejectRatio = (double) rejected / totalInWindow;
            double poorRatio = (double) poorOrInvalid / totalInWindow;
            boolean degraded = rejectRatio >= DEGRADE_REJECT_RATIO
                    || poorRatio >= DEGRADE_REJECT_RATIO;

            if (degraded && state != MonitoringState.DEGRADED) {
                transition(session, MonitoringState.DEGRADED);
                raiseAlert(session, AlertType.IOT_SIGNAL_QUALITY_DEGRADED, AlertSeverity.MEDIUM,
                        "Signal quality degraded",
                        String.format(
                                "Monitor %s is producing poor / rejected readings (%d%% of "
                                        + "the last %ds rejected). Check probe placement and "
                                        + "sensor contact.",
                                device.getSerialNumber(),
                                Math.round(Math.max(rejectRatio, poorRatio) * 100),
                                SIGNAL_WINDOW_SECONDS));
                return;
            }
            if (!degraded && state == MonitoringState.DEGRADED) {
                // Recovery: signal is back to acceptable.
                transition(session, MonitoringState.LIVE);
                return;
            }
        }

        // If we got here and state is STARTING but readings ARE flowing
        // (i.e. we found a recent validated reading and didn't STALL),
        // the ingest path normally promotes to LIVE on the first packet.
        // Belt-and-braces fallback in case ingest missed the transition.
        if (state == MonitoringState.STARTING && last.isPresent()) {
            transition(session, MonitoringState.LIVE);
        }
    }

    private void transition(DeviceSession session, MonitoringState next) {
        MonitoringState prev = session.getMonitoringState();
        if (prev == next) return;
        session.transitionState(next);
        sessionRepository.save(session);
        log.info("Monitoring state {} → {} for session {} (visit {})",
                prev, next, session.getId(),
                session.getVisit() != null ? session.getVisit().getVisitNumber() : "?");
    }

    private void maybeRaiseBatteryAlert(DeviceSession session, IoTDevice device) {
        if (device.getBatteryLevel() == null) return;
        if (device.getBatteryLevel() >= LOW_BATTERY_PERCENT) return;
        raiseAlert(session, AlertType.IOT_DEVICE_LOW_BATTERY, AlertSeverity.MEDIUM,
                "Monitor battery low",
                "Monitor " + device.getSerialNumber() + " battery at "
                        + device.getBatteryLevel() + "%. Swap or charge before it disconnects.");
    }

    private void raiseAlert(DeviceSession session, AlertType type, AlertSeverity severity,
                            String title, String message) {
        if (session.getVisit() == null) return;
        boolean alreadyOpen = clinicalAlertRepository
                .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                        session.getVisit().getId(), type);
        if (alreadyOpen) return;

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(session.getVisit())
                .alertType(type)
                .severity(severity)
                .title(title)
                .message(message)
                .autoGenerated(true)
                .build();
        clinicalAlertRepository.save(alert);
        session.incrementAlerts();
    }

    private String deviceLostMessage(DeviceSession session) {
        IoTDevice d = session.getDevice();
        if (session.getVisit() == null) return "Device disconnected.";
        return String.format(
                "Monitor %s for patient %s %s (Visit %s) has lost its heartbeat. "
                        + "The patient is currently not being continuously monitored. "
                        + "Check the device, power it back on, or pair a replacement.",
                d != null ? d.getSerialNumber() : "?",
                session.getVisit().getPatient().getFirstName(),
                session.getVisit().getPatient().getLastName(),
                session.getVisit().getVisitNumber());
    }
}
