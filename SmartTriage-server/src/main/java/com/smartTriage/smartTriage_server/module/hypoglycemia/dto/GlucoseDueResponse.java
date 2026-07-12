package com.smartTriage.smartTriage_server.module.hypoglycemia.dto;

import com.smartTriage.smartTriage_server.module.hypoglycemia.service.GlucoseScheduleService.DueEntry;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the glucose-measurement worklist: a patient on a measurement
 * schedule and where their clock stands. {@code status} is server-computed so
 * every consumer (dashboard, future mobile) ranks patients identically:
 * OVERDUE (grace elapsed) → DUE (reading due now) → SCHEDULED (countdown).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlucoseDueResponse {

    private UUID visitId;
    private String visitNumber;
    private String patientName;
    private String currentZone;
    private String currentBedLabel;

    private String tierKey;
    private String tierLabel;
    private long intervalMinutes;

    /** Latest glucose from ANY source; null when nothing has ever been recorded. */
    private Instant lastReadingAt;
    private Instant dueAt;
    /** When the overdue escalation (zone doctor + charge nurse) engages. */
    private Instant escalateAt;
    /** Negative once past due. */
    private long minutesUntilDue;
    /** OVERDUE / DUE / SCHEDULED. */
    private String status;

    public static GlucoseDueResponse from(DueEntry entry, Instant now) {
        Visit visit = entry.visit();
        return GlucoseDueResponse.builder()
                .visitId(visit.getId())
                .visitNumber(visit.getVisitNumber())
                .patientName(visit.getPatient() != null
                        ? visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName() : null)
                .currentZone(visit.getCurrentEdZone() != null ? visit.getCurrentEdZone().name() : null)
                .currentBedLabel(visit.getCurrentBed() != null ? visit.getCurrentBed().getCode() : null)
                .tierKey(entry.tier().key())
                .tierLabel(entry.tier().label())
                .intervalMinutes(entry.tier().interval().toMinutes())
                .lastReadingAt(entry.lastReadingAt())
                .dueAt(entry.dueAt())
                .escalateAt(entry.escalateAt())
                .minutesUntilDue(Duration.between(now, entry.dueAt()).toMinutes())
                .status(entry.overdue(now) ? "OVERDUE" : entry.due(now) ? "DUE" : "SCHEDULED")
                .build();
    }
}
