package com.smartTriage.smartTriage_server.module.iot.service;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.MobilityStatus;
import com.smartTriage.smartTriage_server.common.enums.TraumaStatus;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.dto.MonitoringEventResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.MonitoringInsightsResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.MonitoringInsightsResponse.Baseline;
import com.smartTriage.smartTriage_server.module.iot.dto.MonitoringInsightsResponse.Bucket;
import com.smartTriage.smartTriage_server.module.iot.dto.MonitoringInsightsResponse.Event;
import com.smartTriage.smartTriage_server.module.iot.entity.MonitoringEvent;
import com.smartTriage.smartTriage_server.module.iot.repository.MonitoringEventRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.VitalStreamRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.VitalStreamRepository.VitalBucket;
import com.smartTriage.smartTriage_server.module.triage.engine.PediatricTewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.engine.TewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MonitoringInsightsService — reconstructs the patient's monitoring STORY
 * for the Full Monitoring View: time-bucketed vitals with per-bucket TEWS
 * and trend labels (the "how have they been doing for the last N hours"
 * band a doctor actually asks for), plus the clinical event markers that
 * happened along the way and the arrival baseline for delta chips.
 *
 * <p>Trend labels here are a DISPLAY-LAYER reconstruction from bucket
 * averages, using the same destination-aware philosophy as the live
 * engine (a steep slope only "worsens" when heading INTO an abnormal
 * band). The authoritative current trend remains the engine's
 * hysteresis-backed session classification; this service exists so
 * history is viewable retroactively without persisting every transition.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonitoringInsightsService {

    private final VitalStreamRepository streamRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final ClinicalAlertRepository alertRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final MonitoringEventRepository monitoringEventRepository;
    private final VisitRepository visitRepository;
    private final TewsCalculator tewsCalculator;
    private final PediatricTewsCalculator pediatricTewsCalculator;

    private static final int MAX_EVENTS = 60;

    public MonitoringInsightsResponse getInsights(UUID visitId, int hours, int bucketMinutes) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));

        int clampedHours = Math.max(1, Math.min(hours, 24));
        int clampedBucket = Math.max(1, Math.min(bucketMinutes, 60));
        Instant now = Instant.now();
        Instant from = now.minus(clampedHours, ChronoUnit.HOURS);

        // ── Bucketed vitals ──
        List<VitalBucket> raw = streamRepository.aggregateBuckets(
                visitId, from, clampedBucket * 60L);
        List<Bucket> buckets = new ArrayList<>(raw.size());
        VitalBucket prev = null;
        for (VitalBucket b : raw) {
            buckets.add(Bucket.builder()
                    .start(Instant.ofEpochSecond(b.getBucketEpoch()))
                    .hr(b.getHr())
                    .spo2(b.getSpo2())
                    .rr(b.getRr())
                    .sbp(b.getSbp())
                    .dbp(b.getDbp())
                    .temp(b.getTemp())
                    .tews(computeTews(b, visit.isPediatric()))
                    .trend(classifyBucket(prev, b))
                    .readings(b.getN() != null ? b.getN() : 0)
                    .build());
            prev = b;
        }

        // ── Event markers ──
        List<Event> events = new ArrayList<>();
        for (ClinicalAlert a : alertRepository
                .findByVisitIdAndIsActiveTrueAndAutoGeneratedTrueAndCreatedAtAfterOrderByCreatedAtAsc(
                        visitId, from)) {
            events.add(Event.builder()
                    .at(a.getCreatedAt())
                    .kind("ALERT")
                    .label(a.getTitle() != null ? a.getTitle() : a.getAlertType().name())
                    .severity(a.getSeverity() != null ? a.getSeverity().name() : null)
                    .build());
        }
        for (TriageRecord t : triageRecordRepository
                .findByVisitIdAndIsActiveTrueAndTriageTimeAfterOrderByTriageTimeAsc(visitId, from)) {
            String fromCat = t.getPreviousCategory() != null ? t.getPreviousCategory().name() : "—";
            String label = t.isRetriage()
                    ? "Re-triage " + fromCat + " → " + t.getTriageCategory().name()
                            + (t.isSystemTriggered() ? " (auto)" : "")
                    : "Triaged " + t.getTriageCategory().name();
            events.add(Event.builder()
                    .at(t.getTriageTime())
                    .kind("RETRIAGE")
                    .label(label)
                    .severity(t.getTriageCategory().name())
                    .build());
        }
        // Monitoring-engine detections (V119). Only PATTERN transitions go on
        // the journey bar: alerts/retriage above already mark the paged
        // moments, and pattern events add the dedup-suppressed detections the
        // alert layer deliberately swallows. TREND_CHANGED is excluded (the
        // band itself IS the trend) and SESSION_* stays in the history panel.
        for (MonitoringEvent me : monitoringEventRepository
                .findByVisitIdAndIsActiveTrueAndOccurredAtAfterOrderByOccurredAtAsc(visitId, from)) {
            String type = me.getEventType() != null ? me.getEventType().name() : "";
            if (!type.equals("PATTERN_DETECTED") && !type.equals("PATTERN_CLEARED")) continue;
            events.add(Event.builder()
                    .at(me.getOccurredAt())
                    .kind("DETECTION")
                    .label(me.getLabel())
                    .severity(type.equals("PATTERN_DETECTED") ? "HIGH" : "LOW")
                    .build());
        }

        events.sort((x, y) -> x.getAt().compareTo(y.getAt()));
        if (events.size() > MAX_EVENTS) {
            log.debug("Insights events for visit {} truncated {} → {}", visitId, events.size(), MAX_EVENTS);
            events = events.subList(events.size() - MAX_EVENTS, events.size());
        }

        // ── Arrival baseline ──
        Baseline baseline = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtAsc(visitId)
                .map(MonitoringInsightsService::toBaseline)
                .orElse(null);

        return MonitoringInsightsResponse.builder()
                .bucketMinutes(clampedBucket)
                .fromTime(from)
                .toTime(now)
                .buckets(buckets)
                .events(events)
                .baseline(baseline)
                .build();
    }

    /**
     * The full monitoring event log for a visit (V119) — every recorded
     * transition in the window, ascending. The Full Monitoring View's
     * "Event history" panel; the answer to "what happened, in what order?".
     */
    public List<MonitoringEventResponse> getEventHistory(UUID visitId, int hours) {
        int clampedHours = Math.max(1, Math.min(hours, 72));
        Instant from = Instant.now().minus(clampedHours, ChronoUnit.HOURS);
        return monitoringEventRepository
                .findByVisitIdAndIsActiveTrueAndOccurredAtAfterOrderByOccurredAtAsc(visitId, from)
                .stream()
                .map(MonitoringEventResponse::from)
                .toList();
    }

    private static Baseline toBaseline(VitalSigns v) {
        return Baseline.builder()
                .recordedAt(v.getRecordedAt())
                .hr(v.getHeartRate())
                .spo2(v.getSpo2())
                .rr(v.getRespiratoryRate())
                .sbp(v.getSystolicBp())
                .dbp(v.getDiastolicBp())
                .temp(v.getTemperature())
                .build();
    }

    private Integer computeTews(VitalBucket b, boolean pediatric) {
        try {
            VitalSigns synthetic = VitalSigns.builder()
                    .heartRate(b.getHr())
                    .spo2(b.getSpo2())
                    .respiratoryRate(b.getRr())
                    .systolicBp(b.getSbp())
                    .diastolicBp(b.getDbp())
                    .temperature(b.getTemp())
                    .build();
            return pediatric
                    ? pediatricTewsCalculator.calculatePediatricTewsScore(
                            synthetic, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA)
                    : tewsCalculator.calculateTewsScore(
                            synthetic, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Display-layer trend reconstruction (destination-aware) ──

    private String classifyBucket(VitalBucket prev, VitalBucket cur) {
        if (inCriticalBand(cur)) return "WORSENING";
        if (abnormalCount(cur) >= 2) return "WORSENING";

        if (prev != null) {
            boolean worse = false;
            boolean better = false;
            if (cur.getHr() != null && prev.getHr() != null) {
                int d = cur.getHr() - prev.getHr();
                if (d > 15 && cur.getHr() > 90) worse = true;
                else if (d < -15 && prev.getHr() > 100 && cur.getHr() >= 55) better = true;
            }
            if (cur.getRr() != null && prev.getRr() != null) {
                int d = cur.getRr() - prev.getRr();
                if (d > 4 && cur.getRr() > 18) worse = true;
                else if (d < -4 && prev.getRr() > 20) better = true;
            }
            if (cur.getSpo2() != null && prev.getSpo2() != null) {
                int d = cur.getSpo2() - prev.getSpo2();
                if (d < -3 && cur.getSpo2() < 96) worse = true;
                else if (d > 3 && prev.getSpo2() < 94) better = true;
            }
            if (cur.getSbp() != null && prev.getSbp() != null) {
                int d = cur.getSbp() - prev.getSbp();
                if (d < -15 && cur.getSbp() < 120) worse = true;
                else if (d > 15 && prev.getSbp() < 100) better = true;
            }
            if (worse) return "WORSENING";
            if (better) return "IMPROVING";
        }
        // A single-vital abnormality without slope still reads as "watch"
        // territory; keep the band honest but calm: STABLE.
        return "STABLE";
    }

    private static boolean inCriticalBand(VitalBucket r) {
        if (r.getHr() != null && (r.getHr() > 130 || r.getHr() < 40)) return true;
        if (r.getRr() != null && r.getRr() > 30) return true;
        if (r.getSpo2() != null && r.getSpo2() < 92) return true;
        if (r.getSbp() != null && (r.getSbp() < 70 || r.getSbp() > 200)) return true;
        if (r.getTemp() != null && (r.getTemp() > 40.0 || r.getTemp() < 34.0)) return true;
        return false;
    }

    private static int abnormalCount(VitalBucket r) {
        int c = 0;
        if (r.getHr() != null && (r.getHr() > 110 || r.getHr() < 50)) c++;
        if (r.getRr() != null && (r.getRr() > 20 || r.getRr() < 9)) c++;
        if (r.getSpo2() != null && r.getSpo2() < 95) c++;
        if (r.getSbp() != null && (r.getSbp() > 199 || r.getSbp() < 80)) c++;
        if (r.getTemp() != null && (r.getTemp() > 38.5 || r.getTemp() < 35.0)) c++;
        return c;
    }
}
