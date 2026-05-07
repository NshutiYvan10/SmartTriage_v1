package com.smartTriage.smartTriage_server.module.shift.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/shifts/hospital/{hospitalId}/copy-week}.
 *
 * <p>Copies every active assignment row in the source week to the same
 * (zone, function, period, shift-lead, day-of-week-offset) slot in the target
 * week. Both dates MUST be Mondays — the server validates this rather than
 * silently rounding, because a charge nurse asking to copy "this week" to
 * "next week" expects exactly seven full days, not a 6- or 8-day window.
 *
 * <p>Per-row behaviour during copy:
 * <ul>
 *   <li>Users with approved leave covering the target date are skipped (their
 *       row is dropped from the target shift, not carried over).</li>
 *   <li>If the target day already has any active assignments for that period,
 *       the copy is skipped for that (date, period) — a CN will not silently
 *       overwrite a roster they may have already hand-edited. The response
 *       reports which slots were skipped so the UI can show them.</li>
 *   <li>After all rows land, the materialiser's {@code ensureActingShiftLead}
 *       runs per (date, period) so no shift ends up with no lead.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CopyWeekRequest {

    @NotNull(message = "fromWeekStart is required")
    private LocalDate fromWeekStart;

    @NotNull(message = "toWeekStart is required")
    private LocalDate toWeekStart;
}
