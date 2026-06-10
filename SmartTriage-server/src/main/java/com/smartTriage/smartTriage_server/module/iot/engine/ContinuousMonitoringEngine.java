package com.smartTriage.smartTriage_server.module.iot.engine;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.enums.TrendStatus;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.alert.service.AlertEscalationService;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.VitalStream;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.VitalStreamRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.iot.service.VitalStreamService;
import com.smartTriage.smartTriage_server.module.triage.engine.TewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.engine.PediatricTewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ContinuousMonitoringEngine — AI-driven deterioration detection and auto-retriage.
 *
 * This is the brain of the IoT monitoring system. It analyses recent VitalStream
 * data to detect clinical deterioration patterns and triggers automatic retriage
 * when thresholds are crossed.
 *
 * Detection strategies:
 *   1. SINGLE_VITAL_CRITICAL — any single vital in the critical range
 *   2. MULTI_VITAL_TREND — ≥2 vitals trending abnormally simultaneously
 *   3. RAPID_DECLINE — vital value dropping faster than expected
 *   4. SUSTAINED_ABNORMALITY — abnormal value sustained over a time window
 *   5. SPO2_OVERRIDE — SpO2 < 92% always triggers RED (per Rwanda protocol)
 *   6. TEWS_ESCALATION — computed TEWS from stream data exceeds current score
 *
 * Integration:
 *   - Reads from VitalStream (high-frequency IoT data)
 *   - Creates VitalSigns snapshots (clinical-grade records)
 *   - Creates TriageRecords with isSystemTriggered=true
 *   - Generates ClinicalAlerts for all detected deterioration
 *   - Updates DeviceSession statistics
 *
 * CRITICAL: This engine makes clinical decisions. Conservative thresholds are
 * used — the system favours false positives (escalation) over false negatives
 * (missed deterioration). A clinician must always confirm auto-retriage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContinuousMonitoringEngine {

    private final VitalStreamRepository streamRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final VisitRepository visitRepository;
    private final DeviceSessionRepository sessionRepository;
    private final VitalStreamService vitalStreamService;
    private final TewsCalculator tewsCalculator;
    private final PediatricTewsCalculator pediatricTewsCalculator;
    private final RealTimeEventPublisher eventPublisher;
    private final AlertEscalationService alertEscalationService;

    // ====================================================================
    // CRITICAL THRESHOLDS (from Rwanda National Triage Protocol)
    // These match the RED column in the TEWS grid (score = 3)
    // ====================================================================
    private static final int CRITICAL_HR_HIGH = 130;
    private static final int CRITICAL_HR_LOW = 40;
    private static final int CRITICAL_RR_HIGH = 30;
    private static final int CRITICAL_SPO2 = 92;     // Rwanda protocol: SpO2 < 92 → RED
    private static final int CRITICAL_SBP_LOW = 70;
    private static final int CRITICAL_SBP_HIGH = 200;
    private static final double CRITICAL_TEMP_HIGH = 40.0;
    private static final double CRITICAL_TEMP_LOW = 34.0;

    // Trend analysis windows
    private static final int TREND_WINDOW_MINUTES = 5;
    private static final int TREND_MIN_READINGS = 3;

    /**
     * Minimum number of validated readings before a non-SpO2 critical
     * branch may fire. Phase 3 safety guard against single-reading
     * artefacts (probe contacting bed sheet, brief signal dropout).
     * SpO2 &lt; 92 is intentionally exempt — Rwandan protocol treats
     * it as a per-reading red flag and an artefact there is rare
     * enough that over-triggering is the safer side.
     */
    private static final int MIN_READINGS_FOR_ALERT = 2;

    // Auto-retriage cooldown (don't trigger more than once per interval)
    private static final int RETRIAGE_COOLDOWN_MINUTES = 10;

    /**
     * Result of a monitoring analysis cycle.
     */
    public record MonitoringResult(
            boolean deteriorationDetected,
            DeteriorationPattern pattern,
            String description,
            boolean retriageTriggered,
            TriageCategory suggestedCategory,
            int alertsGenerated
    ) {}

    // ====================================================================
    // MAIN ANALYSIS ENTRY POINT
    // ====================================================================

    /**
     * Analyse the latest readings for a visit and detect deterioration.
     * Called after each validated reading is ingested.
     *
     * @param visitId  the visit to analyse
     * @param session  the active device session
     * @return MonitoringResult with detection findings
     */
    @Transactional
    public MonitoringResult analyseAndRespond(UUID visitId, DeviceSession detachedSession) {
        // The session passed in from the controller was loaded in a prior
        // transaction, so its lazy Visit proxy is detached. Re-fetch inside
        // this transaction so Visit/Patient access works without LazyInit errors.
        DeviceSession session = sessionRepository.findById(detachedSession.getId())
                .orElse(detachedSession);
        Visit visit = session.getVisit();

        // ── Phase 3 safety guards ──
        //
        // 1. Monitoring state — only LIVE / DEGRADED sessions feed the
        //    deterioration + auto-retriage engines. The new
        //    MonitoringState.allowsAutoRetriage() centralises this rule
        //    so STARTING (warm-up), STALLED, PAUSED, DISCONNECTED and
        //    ENDED sessions never trip an alert. Prevents the
        //    "auto-retriage fires on the first noisy reading before
        //    the nurse seated the SpO2 probe" failure mode that drove
        //    the previous "auto-start at placement" model.
        if (session.getMonitoringState() == null
                || !session.getMonitoringState().allowsAutoRetriage()) {
            return new MonitoringResult(false, DeteriorationPattern.NONE,
                    "Session not in an auto-retriage-eligible state: "
                            + session.getMonitoringState(),
                    false, null, 0);
        }

        Instant windowStart = Instant.now().minus(TREND_WINDOW_MINUTES, ChronoUnit.MINUTES);
        Instant windowEnd = Instant.now();

        // Get recent validated readings for trend analysis
        List<VitalStream> recentReadings = streamRepository.findValidatedInTimeRange(
                visitId, windowStart, windowEnd);

        if (recentReadings.isEmpty()) {
            return new MonitoringResult(false, DeteriorationPattern.NONE,
                    "No recent readings", false, null, 0);
        }

        // 2. Minimum-readings guard — single-reading anomalies don't
        //    drive auto-retriage. A probe that briefly contacts the
        //    bed sheet (HR=0) or a transient artefact can produce
        //    one critical reading. Require ≥2 validated readings in
        //    the trend window before any "single vital critical"
        //    branch fires. SpO2 < 92 still uses the per-reading
        //    Rwandan-protocol override below — that one is too
        //    safety-critical to gate.
        boolean meetsMinReadings = recentReadings.size() >= MIN_READINGS_FOR_ALERT;

        VitalStream latest = recentReadings.getLast(); // most recent (ASC order)
        List<String> findings = new ArrayList<>();
        DeteriorationPattern detectedPattern = DeteriorationPattern.NONE;
        boolean critical = false;
        int alertCount = 0;

        // --- Check 1: SpO2 Override (per Rwanda protocol, SpO2 < 92 → RED) ---
        if (latest.getSpo2() != null && latest.getSpo2() < CRITICAL_SPO2) {
            critical = true;
            detectedPattern = DeteriorationPattern.SPO2_OVERRIDE;
            findings.add("SpO2 critically low: " + latest.getSpo2() + "% (< " + CRITICAL_SPO2 + "%)");
        }

        // --- Check 2: Single vital critical ---
        // Min-readings gate: a single critical reading is plausibly
        // an artefact (probe-bed contact, brief dropout). Wait for
        // confirmation. Multi-vital and rapid-decline branches below
        // have their own MIN_READINGS (3) gate; SpO2 < 92 above is
        // exempt by Rwandan protocol.
        if (!critical && meetsMinReadings) {
            String singleCritical = checkSingleVitalCritical(latest);
            if (singleCritical != null) {
                // Require the second-to-last reading to also be in the
                // critical band for the same vital, before we fire.
                VitalStream prev = recentReadings.get(recentReadings.size() - 2);
                if (checkSingleVitalCritical(prev) != null) {
                    critical = true;
                    detectedPattern = DeteriorationPattern.SINGLE_VITAL_CRITICAL;
                    findings.add(singleCritical + " (confirmed across 2 readings)");
                }
            }
        }

        // --- Check 3: Multi-vital trend (≥2 vitals abnormal) ---
        if (!critical && recentReadings.size() >= TREND_MIN_READINGS) {
            int abnormalCount = countAbnormalVitals(latest);
            if (abnormalCount >= 2) {
                detectedPattern = DeteriorationPattern.MULTI_VITAL_TREND;
                findings.add(abnormalCount + " vitals simultaneously abnormal");
                critical = true;
            }
        }

        // --- Check 4: Rapid decline ---
        if (!critical && recentReadings.size() >= TREND_MIN_READINGS) {
            String rapidDecline = checkRapidDecline(recentReadings);
            if (rapidDecline != null) {
                detectedPattern = DeteriorationPattern.RAPID_DECLINE;
                findings.add(rapidDecline);
                critical = true;
            }
        }

        // --- Check 5: TEWS escalation (compute TEWS from stream data) ---
        if (!critical) {
            int streamTews = computeTewsFromStream(latest, visit.isPediatric());
            Integer currentTews = visit.getCurrentTewsScore();
            if (currentTews != null && streamTews > currentTews + 2) {
                // TEWS increased by more than 2 points → significant deterioration
                detectedPattern = DeteriorationPattern.RAPID_DECLINE;
                findings.add("TEWS increased from " + currentTews + " to " + streamTews);
                critical = true;
            }
        }

        if (!critical) {
            // Still classify trend so the dashboard reflects gradual drift
            // (e.g. "worsening" long before a RED threshold is crossed).
            updateTrendClassification(session, recentReadings, false);
            sessionRepository.save(session);
            return new MonitoringResult(false, DeteriorationPattern.NONE,
                    "All vitals within acceptable range", false, null, 0);
        }

        // ================================================================
        // DETERIORATION DETECTED — Generate alert and consider auto-retriage
        // ================================================================
        String description = String.join("; ", findings);
        log.warn("DETERIORATION DETECTED — Visit {} | Pattern: {} | {}",
                visit.getVisitNumber(), detectedPattern, description);

        // Dedup: the engine runs on every vitals packet (every few seconds).
        // Without this guard, a patient who stays critical for 10 minutes
        // produces 100+ duplicate alerts. Only create (and broadcast) a new
        // deterioration alert when there is no unacknowledged one already
        // open for this visit — the single open alert is kept fresh and the
        // clinician acknowledges once. If the clinician acks and the patient
        // then deteriorates again, a new alert fires (correct behaviour).
        boolean alreadyOpen = clinicalAlertRepository
                .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                        visit.getId(), AlertType.DETERIORATION_DETECTED);
        if (!alreadyOpen) {
            ClinicalAlert saved = generateDeteriorationAlert(visit, detectedPattern, description);
            try {
                ClinicalAlertResponse response = ClinicalAlertMapper.toResponse(saved);
                UUID hospitalId = visit.getPatient().getHospital().getId();
                eventPublisher.publishHospitalAlert(hospitalId, response);
                TriageCategory currentCat = visit.getCurrentTriageCategory();
                if (currentCat != null) {
                    eventPublisher.publishZoneAlert(hospitalId,
                            EdZone.fromTriageCategory(currentCat), response);
                }
            } catch (Exception e) {
                log.warn("Failed to publish deterioration alert for visit {}: {}",
                        visit.getVisitNumber(), e.getMessage());
            }
            alertCount++;
            session.incrementAlerts();
        } else {
            log.debug("Deterioration alert already open for visit {}, skipping duplicate",
                    visit.getVisitNumber());
        }

        // Check if auto-retriage should be triggered
        boolean retriageTriggered = false;
        TriageCategory suggestedCategory = null;

        if (shouldTriggerRetriage(visit)) {
            VitalSigns snapshot = vitalStreamService.createVitalSnapshot(visitId, latest.getDeviceId());
            if (snapshot != null) {
                suggestedCategory = determineTriageCategory(snapshot, visit.isPediatric());
                TriageCategory currentCategory = visit.getCurrentTriageCategory();

                // Only escalate (never downgrade via auto-retriage)
                if (currentCategory == null
                        || suggestedCategory.getSeverity() > currentCategory.getSeverity()) {

                    performAutoRetriage(visit, snapshot, suggestedCategory, detectedPattern, description);
                    retriageTriggered = true;
                    session.incrementRetriages();

                    log.warn("AUTO-RETRIAGE: Visit {} | {} → {} | {}",
                            visit.getVisitNumber(), currentCategory, suggestedCategory, description);
                }
            }
        }

        // Update trend classification with hysteresis (even when critical —
        // a worsening label is still the correct answer in that case).
        updateTrendClassification(session, recentReadings, critical);

        sessionRepository.save(session);

        return new MonitoringResult(true, detectedPattern, description,
                retriageTriggered, suggestedCategory, alertCount);
    }

    // ====================================================================
    // MANUAL VITALS — deterioration check (S3)
    // ====================================================================

    /**
     * Evaluate a single MANUALLY-recorded {@link VitalSigns} reading for
     * critical deterioration and raise a clinical alert if warranted (S3).
     *
     * <p>The IoT path ({@link #analyseAndRespond}) only ever sees
     * high-frequency {@code VitalStream} data from a {@link DeviceSession}.
     * Vitals a clinician types into {@code POST /vitals} never reached any
     * deterioration logic, so a critically abnormal manual reading produced
     * no alert at all. This method closes that gap.
     *
     * <p>Scope is deliberately narrow and conservative:
     * <ul>
     *   <li>Only per-reading CRITICAL findings fire — SpO2 &lt; 92 (Rwanda
     *       protocol) or any single vital in the RED critical band. A single
     *       manual reading carries no trend, so the multi-reading trend /
     *       rapid-decline branches do not apply.</li>
     *   <li>Alerts are de-duplicated against any already-open
     *       DETERIORATION_DETECTED alert for the visit — shared with the IoT
     *       path, so a monitored patient is never double-alerted.</li>
     *   <li>No auto-retriage is performed here: a clinician is actively at
     *       the bedside entering the reading and can retriage. Silent
     *       category changes from manual entry are out of scope for S3.</li>
     * </ul>
     *
     * <p>Best-effort: this method never throws. The caller's vitals write
     * must succeed even if alerting fails.
     */
    public void evaluateManualVitals(Visit visit, VitalSigns vitals) {
        try {
            if (visit == null || vitals == null) return;

            List<String> findings = new ArrayList<>();
            DeteriorationPattern pattern = DeteriorationPattern.NONE;

            // SpO2 override first — highest-regret single vital (Rwanda protocol).
            if (vitals.getSpo2() != null && vitals.getSpo2() < CRITICAL_SPO2) {
                pattern = DeteriorationPattern.SPO2_OVERRIDE;
                findings.add("SpO2 critically low: " + vitals.getSpo2() + "% (< " + CRITICAL_SPO2 + "%)");
            } else {
                String singleCritical = checkSingleVitalCriticalFromVitals(vitals);
                if (singleCritical != null) {
                    pattern = DeteriorationPattern.SINGLE_VITAL_CRITICAL;
                    findings.add(singleCritical);
                }
            }

            if (findings.isEmpty()) {
                return; // nothing critical in this manual reading
            }

            String description = "Manually-recorded vitals — " + String.join("; ", findings);
            log.warn("DETERIORATION (manual vitals) — Visit {} | Pattern: {} | {}",
                    visit.getVisitNumber(), pattern, description);

            // Dedup against any already-open deterioration alert (shared with
            // the IoT engine) so a monitored patient is not double-alerted.
            boolean alreadyOpen = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            visit.getId(), AlertType.DETERIORATION_DETECTED);
            if (alreadyOpen) {
                log.debug("Deterioration alert already open for visit {}, skipping manual-vitals duplicate",
                        visit.getVisitNumber());
                return;
            }

            ClinicalAlert saved = generateDeteriorationAlert(visit, pattern, description);
            try {
                ClinicalAlertResponse response = ClinicalAlertMapper.toResponse(saved);
                UUID hospitalId = visit.getPatient().getHospital().getId();
                eventPublisher.publishHospitalAlert(hospitalId, response);
                TriageCategory currentCat = visit.getCurrentTriageCategory();
                if (currentCat != null) {
                    eventPublisher.publishZoneAlert(hospitalId,
                            EdZone.fromTriageCategory(currentCat), response);
                }
            } catch (Exception e) {
                log.warn("Failed to publish manual-vitals deterioration alert for visit {}: {}",
                        visit.getVisitNumber(), e.getMessage());
            }
        } catch (Exception e) {
            // Best-effort: a deterioration-eval failure must never break the
            // clinician's vitals write.
            log.error("evaluateManualVitals failed for visit {}: {}",
                    visit != null ? visit.getId() : null, e.getMessage(), e);
        }
    }

    /**
     * VitalSigns analogue of {@link #checkSingleVitalCritical(VitalStream)}
     * — identical RED critical-band thresholds, applied to a clinical
     * {@link VitalSigns} snapshot. SpO2 is handled separately by the caller
     * (per-reading protocol override).
     */
    private String checkSingleVitalCriticalFromVitals(VitalSigns reading) {
        if (reading.getHeartRate() != null) {
            if (reading.getHeartRate() > CRITICAL_HR_HIGH) {
                return "Heart rate critically high: " + reading.getHeartRate() + " bpm";
            }
            if (reading.getHeartRate() < CRITICAL_HR_LOW) {
                return "Heart rate critically low: " + reading.getHeartRate() + " bpm";
            }
        }
        if (reading.getRespiratoryRate() != null && reading.getRespiratoryRate() > CRITICAL_RR_HIGH) {
            return "Respiratory rate critically high: " + reading.getRespiratoryRate() + " /min";
        }
        if (reading.getSystolicBp() != null) {
            if (reading.getSystolicBp() < CRITICAL_SBP_LOW) {
                return "Systolic BP critically low: " + reading.getSystolicBp() + " mmHg";
            }
            if (reading.getSystolicBp() > CRITICAL_SBP_HIGH) {
                return "Systolic BP critically high: " + reading.getSystolicBp() + " mmHg";
            }
        }
        if (reading.getTemperature() != null) {
            if (reading.getTemperature() > CRITICAL_TEMP_HIGH) {
                return "Temperature critically high: " + reading.getTemperature() + "°C";
            }
            if (reading.getTemperature() < CRITICAL_TEMP_LOW) {
                return "Temperature critically low: " + reading.getTemperature() + "°C";
            }
        }
        return null;
    }

    // ====================================================================
    // TREND CLASSIFICATION — authoritative, hysteresis-backed
    // ====================================================================

    /**
     * Compute the trend label (WORSENING / STABLE / IMPROVING / UNKNOWN) from
     * recent readings and update the session's persisted trend_status with
     * hysteresis: the raw classification becomes the "candidate"; the stored
     * status only moves when two consecutive ticks agree on the same label.
     * This eliminates flicker from per-reading noise.
     */
    private void updateTrendClassification(DeviceSession session,
                                           List<VitalStream> recentReadings,
                                           boolean deteriorationDetected) {
        TrendStatus raw = classifyTrendRaw(recentReadings, deteriorationDetected);
        TrendStatus current = session.getTrendStatus() != null
                ? session.getTrendStatus() : TrendStatus.UNKNOWN;

        // If the classifier can't decide yet, don't touch what's persisted.
        if (raw == TrendStatus.UNKNOWN) {
            session.setTrendUpdatedAt(Instant.now());
            return;
        }

        // Same as what's already stored → clear any stale candidate, done.
        if (raw == current) {
            session.setTrendCandidate(null);
            session.setTrendUpdatedAt(Instant.now());
            return;
        }

        // Different from stored. Two shortcuts bypass hysteresis:
        //   1. current is UNKNOWN → this is the first real classification, commit now
        //      (otherwise the dashboard shows "stable" for an extra tick on seed).
        //   2. deterioration already detected by the engine this tick → WORSENING
        //      is authoritative, flicker is not a concern, show it immediately.
        boolean immediate = current == TrendStatus.UNKNOWN
                || (deteriorationDetected && raw == TrendStatus.WORSENING);

        // Otherwise require a previous tick to have proposed the SAME new value
        // before we commit. One-step hysteresis smooths slope-driven flicker.
        TrendStatus candidate = session.getTrendCandidate();
        if (immediate || candidate == raw) {
            log.info("Trend change CONFIRMED for visit {}: {} → {}",
                    session.getVisit().getVisitNumber(), current, raw);
            session.setTrendStatus(raw);
            session.setTrendCandidate(null);
            try {
                eventPublisher.publishTrendChange(session.getVisit().getId(),
                        java.util.Map.of(
                                "visitId", session.getVisit().getId().toString(),
                                "sessionId", session.getId().toString(),
                                "trendStatus", raw.name(),
                                "previousTrendStatus", current.name(),
                                "timestamp", Instant.now().toString()));
            } catch (Exception e) {
                log.warn("Failed to publish trend change for visit {}: {}",
                        session.getVisit().getVisitNumber(), e.getMessage());
            }
        } else {
            // First time we see this new label — park it, wait for confirmation.
            session.setTrendCandidate(raw);
        }
        session.setTrendUpdatedAt(Instant.now());
    }

    /**
     * Classify trend from the raw reading window WITHOUT hysteresis.
     * Rules (evaluated top-down, first match wins):
     *   - If the latest reading has any vital in the RED critical band → WORSENING.
     *   - If ≥2 vitals are currently abnormal (TEWS > 0 territory) → WORSENING.
     *   - If any key vital is trending in a dangerous direction over the window
     *     by more than the noise floor → WORSENING.
     *   - If previously-abnormal vitals are returning to normal → IMPROVING.
     *   - Otherwise → STABLE.
     *   - Too few readings → UNKNOWN.
     */
    private TrendStatus classifyTrendRaw(List<VitalStream> readings,
                                         boolean deteriorationDetected) {
        if (readings == null || readings.size() < TREND_MIN_READINGS) {
            return TrendStatus.UNKNOWN;
        }

        VitalStream latest = readings.getLast();
        VitalStream earliest = readings.getFirst();

        // Deterioration engine already flagged this reading as critical → worsening.
        if (deteriorationDetected) {
            return TrendStatus.WORSENING;
        }

        // Current-state check: any RED band value → worsening.
        if (isInCriticalBand(latest)) {
            return TrendStatus.WORSENING;
        }

        // Multiple simultaneously abnormal vitals → worsening.
        if (countAbnormalVitals(latest) >= 2) {
            return TrendStatus.WORSENING;
        }

        // Slope analysis on key vitals. Thresholds are deliberately wider than
        // the simulator / real device noise floors so small jitter doesn't
        // trip the label.
        boolean anyWorseningSlope = false;
        boolean anyImprovingSlope = false;

        // Heart rate: >15 bpm rise over window = worsening; >15 fall from an
        // elevated baseline back toward normal = improving.
        if (latest.getHeartRate() != null && earliest.getHeartRate() != null) {
            int hrDelta = latest.getHeartRate() - earliest.getHeartRate();
            if (hrDelta > 15) anyWorseningSlope = true;
            else if (hrDelta < -15 && earliest.getHeartRate() > 100) anyImprovingSlope = true;
        }

        // Respiratory rate: +4 = worsening, -4 from elevated = improving.
        if (latest.getRespiratoryRate() != null && earliest.getRespiratoryRate() != null) {
            int rrDelta = latest.getRespiratoryRate() - earliest.getRespiratoryRate();
            if (rrDelta > 4) anyWorseningSlope = true;
            else if (rrDelta < -4 && earliest.getRespiratoryRate() > 20) anyImprovingSlope = true;
        }

        // SpO2: -3% = worsening (even while still above 92%), +3 from low = improving.
        if (latest.getSpo2() != null && earliest.getSpo2() != null) {
            int sp = latest.getSpo2() - earliest.getSpo2();
            if (sp < -3) anyWorseningSlope = true;
            else if (sp > 3 && earliest.getSpo2() < 94) anyImprovingSlope = true;
        }

        // Systolic BP: -15 = worsening (shock drift), +15 from low = improving.
        if (latest.getSystolicBp() != null && earliest.getSystolicBp() != null) {
            int sbp = latest.getSystolicBp() - earliest.getSystolicBp();
            if (sbp < -15) anyWorseningSlope = true;
            else if (sbp > 15 && earliest.getSystolicBp() < 100) anyImprovingSlope = true;
        }

        if (anyWorseningSlope) return TrendStatus.WORSENING;
        if (anyImprovingSlope) return TrendStatus.IMPROVING;
        return TrendStatus.STABLE;
    }

    /** True if any single vital in this reading is in the RED critical band. */
    private boolean isInCriticalBand(VitalStream r) {
        if (r.getHeartRate() != null
                && (r.getHeartRate() > CRITICAL_HR_HIGH || r.getHeartRate() < CRITICAL_HR_LOW)) return true;
        if (r.getRespiratoryRate() != null && r.getRespiratoryRate() > CRITICAL_RR_HIGH) return true;
        if (r.getSpo2() != null && r.getSpo2() < CRITICAL_SPO2) return true;
        if (r.getSystolicBp() != null
                && (r.getSystolicBp() < CRITICAL_SBP_LOW || r.getSystolicBp() > CRITICAL_SBP_HIGH)) return true;
        if (r.getTemperature() != null
                && (r.getTemperature() > CRITICAL_TEMP_HIGH || r.getTemperature() < CRITICAL_TEMP_LOW)) return true;
        return false;
    }

    // ====================================================================
    // DETECTION CHECKS
    // ====================================================================

    private String checkSingleVitalCritical(VitalStream reading) {
        if (reading.getHeartRate() != null) {
            if (reading.getHeartRate() > CRITICAL_HR_HIGH) {
                return "Heart rate critically high: " + reading.getHeartRate() + " bpm";
            }
            if (reading.getHeartRate() < CRITICAL_HR_LOW) {
                return "Heart rate critically low: " + reading.getHeartRate() + " bpm";
            }
        }
        if (reading.getRespiratoryRate() != null && reading.getRespiratoryRate() > CRITICAL_RR_HIGH) {
            return "Respiratory rate critically high: " + reading.getRespiratoryRate() + " bpm";
        }
        if (reading.getSystolicBp() != null) {
            if (reading.getSystolicBp() < CRITICAL_SBP_LOW) {
                return "Systolic BP critically low: " + reading.getSystolicBp() + " mmHg";
            }
            if (reading.getSystolicBp() > CRITICAL_SBP_HIGH) {
                return "Systolic BP critically high: " + reading.getSystolicBp() + " mmHg";
            }
        }
        if (reading.getTemperature() != null) {
            if (reading.getTemperature() > CRITICAL_TEMP_HIGH) {
                return "Temperature critically high: " + reading.getTemperature() + "°C";
            }
            if (reading.getTemperature() < CRITICAL_TEMP_LOW) {
                return "Temperature critically low: " + reading.getTemperature() + "°C";
            }
        }
        return null;
    }

    /**
     * Count how many vitals are in abnormal ranges (TEWS score > 0 territory).
     */
    private int countAbnormalVitals(VitalStream reading) {
        int count = 0;
        if (reading.getHeartRate() != null && (reading.getHeartRate() > 110 || reading.getHeartRate() < 50)) count++;
        if (reading.getRespiratoryRate() != null && (reading.getRespiratoryRate() > 20 || reading.getRespiratoryRate() < 9)) count++;
        if (reading.getSpo2() != null && reading.getSpo2() < 95) count++;
        if (reading.getSystolicBp() != null && (reading.getSystolicBp() > 199 || reading.getSystolicBp() < 80)) count++;
        if (reading.getTemperature() != null && (reading.getTemperature() > 38.4 || reading.getTemperature() < 35.0)) count++;
        return count;
    }

    /**
     * Detect rapid decline: a vital dropping/rising significantly over the analysis window.
     * Compares earliest to latest reading in the window.
     */
    private String checkRapidDecline(List<VitalStream> readings) {
        VitalStream earliest = readings.getFirst();
        VitalStream latest = readings.getLast();

        // HR dropping > 30 bpm or rising > 40 bpm in 5 minutes
        if (earliest.getHeartRate() != null && latest.getHeartRate() != null) {
            int hrDelta = latest.getHeartRate() - earliest.getHeartRate();
            if (hrDelta < -30) {
                return "Rapid HR decline: " + earliest.getHeartRate() + " → " + latest.getHeartRate() + " bpm";
            }
            if (hrDelta > 40) {
                return "Rapid HR rise: " + earliest.getHeartRate() + " → " + latest.getHeartRate() + " bpm";
            }
        }

        // SpO2 dropping > 5% in 5 minutes
        if (earliest.getSpo2() != null && latest.getSpo2() != null) {
            int spo2Delta = latest.getSpo2() - earliest.getSpo2();
            if (spo2Delta < -5) {
                return "Rapid SpO2 decline: " + earliest.getSpo2() + "% → " + latest.getSpo2() + "%";
            }
        }

        // RR rising > 10 in 5 minutes
        if (earliest.getRespiratoryRate() != null && latest.getRespiratoryRate() != null) {
            int rrDelta = latest.getRespiratoryRate() - earliest.getRespiratoryRate();
            if (rrDelta > 10) {
                return "Rapid RR increase: " + earliest.getRespiratoryRate() + " → " + latest.getRespiratoryRate();
            }
        }

        // SBP dropping > 30 mmHg in 5 minutes
        if (earliest.getSystolicBp() != null && latest.getSystolicBp() != null) {
            int sbpDelta = latest.getSystolicBp() - earliest.getSystolicBp();
            if (sbpDelta < -30) {
                return "Rapid BP decline: " + earliest.getSystolicBp() + " → " + latest.getSystolicBp() + " mmHg";
            }
        }

        return null;
    }

    // ====================================================================
    // TEWS COMPUTATION FROM STREAM DATA
    // ====================================================================

    /**
     * Compute an approximate TEWS score from stream data.
     * We only have vital sign components (no mobility, AVPU, trauma from IoT).
     * Uses the last known AVPU/mobility/trauma from the latest triage record.
     */
    private int computeTewsFromStream(VitalStream reading, boolean isPediatric) {
        // Build a minimal VitalSigns for TEWS calculation
        VitalSigns syntheticVitals = VitalSigns.builder()
                .heartRate(reading.getHeartRate())
                .respiratoryRate(reading.getRespiratoryRate())
                .spo2(reading.getSpo2())
                .systolicBp(reading.getSystolicBp())
                .temperature(reading.getTemperature())
                .build();

        // Use default values for non-IoT parameters (conservative: assume baseline)
        if (isPediatric) {
            return pediatricTewsCalculator.calculatePediatricTewsScore(
                    syntheticVitals, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        }
        return tewsCalculator.calculateTewsScore(
                syntheticVitals, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
    }

    // ====================================================================
    // AUTO-RETRIAGE
    // ====================================================================

    private boolean shouldTriggerRetriage(Visit visit) {
        // Check cooldown: don't retriage more than once every N minutes
        TriageRecord lastTriage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visit.getId())
                .orElse(null);

        if (lastTriage != null) {
            Instant cooldownEnd = lastTriage.getTriageTime()
                    .plus(RETRIAGE_COOLDOWN_MINUTES, ChronoUnit.MINUTES);
            if (Instant.now().isBefore(cooldownEnd)) {
                log.debug("Retriage cooldown active for visit {} (last triage: {})",
                        visit.getVisitNumber(), lastTriage.getTriageTime());
                return false;
            }
        }

        return true;
    }

    /**
     * Determine triage category based on vital signs (simplified decision engine).
     * Uses critical thresholds that map to RED/ORANGE categories.
     */
    private TriageCategory determineTriageCategory(VitalSigns vitals, boolean isPediatric) {
        // SpO2 < 92 → RED (Rwanda protocol override)
        if (vitals.getSpo2() != null && vitals.getSpo2() < 92) {
            return TriageCategory.RED;
        }

        int tews;
        if (isPediatric) {
            tews = pediatricTewsCalculator.calculatePediatricTewsScore(
                    vitals, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        } else {
            tews = tewsCalculator.calculateTewsScore(
                    vitals, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        }

        // TEWS-based category assignment (conservative — errs toward higher acuity)
        if (tews >= 7) return TriageCategory.RED;
        if (tews >= 5) return TriageCategory.ORANGE;
        if (tews >= 3) return TriageCategory.YELLOW;
        return TriageCategory.GREEN;
    }

    private void performAutoRetriage(Visit visit, VitalSigns snapshot,
                                      TriageCategory newCategory,
                                      DeteriorationPattern pattern,
                                      String description) {
        TriageCategory previousCategory = visit.getCurrentTriageCategory();

        int tews;
        if (visit.isPediatric()) {
            tews = pediatricTewsCalculator.calculatePediatricTewsScore(
                    snapshot, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        } else {
            tews = tewsCalculator.calculateTewsScore(
                    snapshot, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        }

        // Create system-triggered triage record
        TriageRecord record = TriageRecord.builder()
                .visit(visit)
                .vitalSigns(snapshot)
                .triageTime(Instant.now())
                .tewsScore(tews)
                .triageCategory(newCategory)
                .isRetriage(true)
                .isSystemTriggered(true)
                .previousCategory(previousCategory)
                .decisionPath("AUTO-RETRIAGE: " + pattern.name() + " — " + description)
                .clinicalNotes("System-triggered retriage due to IoT-detected deterioration: " + description)
                .build();

        triageRecordRepository.save(record);

        // Update visit
        visit.setCurrentTriageCategory(newCategory);
        visit.setCurrentTewsScore(tews);
        visit.setRetriageCount(visit.getRetriageCount() + 1);
        visitRepository.save(visit);

        // Generate escalation alert
        ClinicalAlert escalationAlert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.TEWS_ESCALATION)
                .severity(newCategory == TriageCategory.RED
                        ? AlertSeverity.CRITICAL : AlertSeverity.HIGH)
                .title("AUTO-RETRIAGE: " + previousCategory + " → " + newCategory)
                .message(String.format(
                        "SYSTEM AUTO-RETRIAGE: Patient %s %s (Visit: %s) " +
                        "escalated from %s to %s by continuous monitoring AI. " +
                        "Pattern: %s. Details: %s. " +
                        "TEWS: %d. IMMEDIATE CLINICAL REVIEW REQUIRED.",
                        visit.getPatient().getFirstName(),
                        visit.getPatient().getLastName(),
                        visit.getVisitNumber(),
                        previousCategory != null ? previousCategory.getDescription() : "NONE",
                        newCategory.getDescription(),
                        pattern.name(),
                        description,
                        tews))
                .autoGenerated(true)
                .build();

        escalationAlert = clinicalAlertRepository.save(escalationAlert);

        // Route through the tiered escalation service so the zone doctor and
        // charge nurse receive targeted WebSocket notifications, and Tier 2/3
        // escalation scheduling kicks in if nobody acknowledges.
        // Dedup the doctor-routing call: if an unacknowledged DOCTOR_NOTIFICATION
        // for this visit already exists, skip creating another to prevent
        // flooding the clinician with repeat Tier 1 pages for the same episode.
        try {
            UUID hospitalId = visit.getPatient().getHospital().getId();
            boolean doctorAlertOpen = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            visit.getId(), AlertType.DOCTOR_NOTIFICATION);
            if (!doctorAlertOpen) {
                alertEscalationService.createZoneRoutedAlert(
                        visit, newCategory, tews,
                        "IoT AUTO-RETRIAGE: " + pattern.name() + " — " + description);
            }
            ClinicalAlertResponse response = ClinicalAlertMapper.toResponse(escalationAlert);
            eventPublisher.publishHospitalAlert(hospitalId, response);
            eventPublisher.publishZoneAlert(hospitalId,
                    EdZone.fromTriageCategory(newCategory), response);
        } catch (Exception e) {
            log.warn("Failed to route auto-retriage alert for visit {}: {}",
                    visit.getVisitNumber(), e.getMessage());
        }
    }

    // ====================================================================
    // ALERT GENERATION
    // ====================================================================

    private ClinicalAlert generateDeteriorationAlert(Visit visit,
                                                      DeteriorationPattern pattern,
                                                      String description) {
        AlertSeverity severity = switch (pattern) {
            case SPO2_OVERRIDE, SINGLE_VITAL_CRITICAL, RESPIRATORY_FAILURE_PATTERN -> AlertSeverity.CRITICAL;
            case RAPID_DECLINE, HEMODYNAMIC_INSTABILITY, SEPSIS_PATTERN -> AlertSeverity.HIGH;
            case MULTI_VITAL_TREND, SUSTAINED_ABNORMALITY -> AlertSeverity.MEDIUM;
            default -> AlertSeverity.LOW;
        };

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.DETERIORATION_DETECTED)
                .severity(severity)
                .title("Deterioration: " + pattern.name().replace("_", " "))
                .message(String.format(
                        "IoT monitoring detected deterioration for patient %s %s (Visit: %s). " +
                        "Pattern: %s. Details: %s.",
                        visit.getPatient().getFirstName(),
                        visit.getPatient().getLastName(),
                        visit.getVisitNumber(),
                        pattern.name(),
                        description))
                .autoGenerated(true)
                .build();

        return clinicalAlertRepository.save(alert);
    }
}
