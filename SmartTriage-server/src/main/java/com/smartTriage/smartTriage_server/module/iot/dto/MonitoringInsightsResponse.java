package com.smartTriage.smartTriage_server.module.iot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Clinical-insights bundle for the Full Monitoring View: the patient's
 * monitoring STORY over a window (default 6 h), not just the live numbers.
 *
 * <ul>
 *   <li>{@code buckets} — time-bucketed vitals averages with a per-bucket
 *       TEWS score and trend label. Drives the journey timeline, the TEWS
 *       trend chart, and the long-range vitals charts. The trend label
 *       here is a display-layer reconstruction using the same
 *       destination-aware rules as the live engine — the authoritative
 *       CURRENT trend remains the session's engine-classified one.</li>
 *   <li>{@code events} — timestamped clinical markers in the window:
 *       system alerts (deterioration, sepsis pattern, auto-retriage) and
 *       re-triage records. Pinned onto the journey timeline.</li>
 *   <li>{@code baseline} — the visit's EARLIEST recorded vitals (arrival/
 *       triage), so tiles can show change-since-arrival deltas.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitoringInsightsResponse {

    private int bucketMinutes;
    private Instant fromTime;
    private Instant toTime;
    private List<Bucket> buckets;
    private List<Event> events;
    private Baseline baseline;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bucket {
        private Instant start;
        private Integer hr;
        private Integer spo2;
        private Integer rr;
        private Integer sbp;
        private Integer dbp;
        private Double temp;
        /** Approximate TEWS from the bucket's averaged vitals (defaults for AVPU/mobility/trauma). */
        private Integer tews;
        /** WORSENING | STABLE | IMPROVING — display-layer reconstruction. */
        private String trend;
        private long readings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        private Instant at;
        /** ALERT | RETRIAGE */
        private String kind;
        private String label;
        /** AlertSeverity name for ALERT events; triage category name for RETRIAGE. */
        private String severity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Baseline {
        private Instant recordedAt;
        private Integer hr;
        private Integer spo2;
        private Integer rr;
        private Integer sbp;
        private Integer dbp;
        private Double temp;
    }
}
