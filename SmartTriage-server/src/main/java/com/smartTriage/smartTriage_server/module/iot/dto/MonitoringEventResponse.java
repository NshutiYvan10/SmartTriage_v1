package com.smartTriage.smartTriage_server.module.iot.dto;

import com.smartTriage.smartTriage_server.module.iot.entity.MonitoringEvent;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One monitoring event-log entry (V119) — a transition the monitoring
 * engine recorded, with the vitals context that triggered it.
 */
@Getter
@Builder
public class MonitoringEventResponse {

    private UUID id;
    private UUID sessionId;
    private String eventType;
    private String label;
    private String detail;
    private Instant occurredAt;
    private Integer heartRate;
    private Integer spo2;
    private Integer respiratoryRate;
    private Integer systolicBp;
    private Double temperature;

    public static MonitoringEventResponse from(MonitoringEvent e) {
        return MonitoringEventResponse.builder()
                .id(e.getId())
                .sessionId(e.getSessionId())
                .eventType(e.getEventType() != null ? e.getEventType().name() : null)
                .label(e.getLabel())
                .detail(e.getDetail())
                .occurredAt(e.getOccurredAt())
                .heartRate(e.getHeartRate())
                .spo2(e.getSpo2())
                .respiratoryRate(e.getRespiratoryRate())
                .systolicBp(e.getSystolicBp())
                .temperature(e.getTemperature())
                .build();
    }
}
