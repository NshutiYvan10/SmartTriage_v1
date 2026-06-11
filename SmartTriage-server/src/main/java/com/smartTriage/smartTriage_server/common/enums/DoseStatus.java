package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Lifecycle status of a single dose event (Medication Management, V67).
 *
 * <p>One {@code MedicationDose} row = one administration opportunity.
 * A delay is NOT a separate status — a delayed dose stays {@link #DUE}
 * with its {@code dueAt} pushed forward, {@code delayCount}
 * incremented, and the reason appended to {@code statusReason}, so the
 * dose keeps flowing through the overdue/missed monitoring.
 */
@Getter
@RequiredArgsConstructor
public enum DoseStatus {

    /** Waiting to be given (includes overdue — overdue is DUE past its time). */
    DUE("Due"),
    /** Administered and recorded. */
    GIVEN("Given"),
    /** Patient refused this dose (order may stay active for the next one). */
    REFUSED("Refused"),
    /** Never given — escalated past the missed threshold without administration. */
    MISSED("Missed"),
    /** Withdrawn before administration (order discontinued / held / superseded). */
    CANCELLED("Cancelled");

    private final String label;
}
