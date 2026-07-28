package com.smartTriage.smartTriage_server.module.iot.service;

import com.smartTriage.smartTriage_server.common.enums.MonitoringEventType;
import com.smartTriage.smartTriage_server.module.iot.entity.MonitoringEvent;
import com.smartTriage.smartTriage_server.module.iot.entity.VitalStream;
import com.smartTriage.smartTriage_server.module.iot.repository.MonitoringEventRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MonitoringEventRecorder — appends monitoring_events rows (V119).
 *
 * Best-effort by contract: recording history must never break the
 * clinical action it documents (detection, retriage, session change),
 * so every failure is swallowed and logged. Joins the caller's
 * transaction — if the analysis rolls back, its events must roll back
 * with it (a phantom "sepsis detected" row with no matching detection
 * would be worse than a missing one).
 *
 * After saving, a lightweight nudge is published on the visit's trend
 * topic ({@code type: MONITORING_EVENT}) so open monitoring views can
 * refresh their event history immediately instead of waiting for the
 * next poll.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringEventRecorder {

    private final MonitoringEventRepository repository;
    private final RealTimeEventPublisher eventPublisher;

    /** Record with vitals context from a stream reading (may be null). */
    public void record(Visit visit, UUID sessionId, MonitoringEventType type,
                       String label, String detail, VitalStream context) {
        try {
            MonitoringEvent event = MonitoringEvent.builder()
                    .visit(visit)
                    .sessionId(sessionId)
                    .eventType(type)
                    .label(truncate(label, 160))
                    .detail(detail)
                    .occurredAt(Instant.now())
                    .heartRate(context != null ? context.getHeartRate() : null)
                    .spo2(context != null ? context.getSpo2() : null)
                    .respiratoryRate(context != null ? context.getRespiratoryRate() : null)
                    .systolicBp(context != null ? context.getSystolicBp() : null)
                    .temperature(context != null ? context.getTemperature() : null)
                    .build();
            repository.save(event);

            // Nudge open monitoring views. HashMap — several fields nullable.
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "MONITORING_EVENT");
            payload.put("visitId", visit.getId().toString());
            payload.put("eventType", type.name());
            payload.put("label", event.getLabel());
            payload.put("occurredAt", event.getOccurredAt().toString());
            eventPublisher.publishTrendChange(visit.getId(), payload);
        } catch (Exception e) {
            log.warn("Failed to record monitoring event {} for visit {}: {}",
                    type, visit != null ? visit.getId() : null, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
