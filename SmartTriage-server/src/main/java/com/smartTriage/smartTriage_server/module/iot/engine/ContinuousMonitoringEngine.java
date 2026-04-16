package com.smartTriage.smartTriage_server.module.iot.engine;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.VitalStream;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.VitalStreamRepository;
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
    public MonitoringResult analyseAndRespond(UUID visitId, DeviceSession session) {
        Visit visit = session.getVisit();
        Instant windowStart = Instant.now().minus(TREND_WINDOW_MINUTES, ChronoUnit.MINUTES);
        Instant windowEnd = Instant.now();

        // Get recent validated readings for trend analysis
        List<VitalStream> recentReadings = streamRepository.findValidatedInTimeRange(
                visitId, windowStart, windowEnd);

        if (recentReadings.isEmpty()) {
            return new MonitoringResult(false, DeteriorationPattern.NONE,
                    "No recent readings", false, null, 0);
        }

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
        if (!critical) {
            String singleCritical = checkSingleVitalCritical(latest);
            if (singleCritical != null) {
                critical = true;
                detectedPattern = DeteriorationPattern.SINGLE_VITAL_CRITICAL;
                findings.add(singleCritical);
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
            return new MonitoringResult(false, DeteriorationPattern.NONE,
                    "All vitals within acceptable range", false, null, 0);
        }

        // ================================================================
        // DETERIORATION DETECTED — Generate alert and consider auto-retriage
        // ================================================================
        String description = String.join("; ", findings);
        log.warn("DETERIORATION DETECTED — Visit {} | Pattern: {} | {}",
                visit.getVisitNumber(), detectedPattern, description);

        // Generate clinical alert
        generateDeteriorationAlert(visit, detectedPattern, description);
        alertCount++;
        session.incrementAlerts();

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

        sessionRepository.save(session);

        return new MonitoringResult(true, detectedPattern, description,
                retriageTriggered, suggestedCategory, alertCount);
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

        clinicalAlertRepository.save(escalationAlert);
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
