package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Request body for creating a shift assignment.
 *
 * <p>{@code shiftDate} and {@code shiftPeriod} are optional. When omitted,
 * the assignment lands on the current shift (computed server-side via
 * {@code ShiftAssignmentService.getCurrentShiftDate / getCurrentShiftPeriod}).
 * When provided, both must be set together — they target a specific
 * future shift, which is how the calendar's quick-assign drawer schedules
 * Friday's roster from inside Wednesday's UI.
 *
 * <p>Past dates are rejected server-side. Backdating roster history would
 * corrupt audit trails (it would let someone retroactively place a staff
 * member at a zone where a clinical-action audit log says they were
 * absent). A separate "edit historical roster" feature, if ever needed,
 * would require its own permission gate and is intentionally not exposed
 * through this DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateShiftAssignmentRequest {

    @NotNull(message = "User ID is required")
    private UUID userId;

    @NotNull(message = "Zone is required")
    private EdZone zone;

    /**
     * Workflow 4 — additional zones this clinician also covers on
     * this shift, beyond the primary {@link #zone}. Optional; null
     * or an empty set means single-zone coverage (the legacy
     * default).
     *
     * <p>Must not include the primary zone — the service rejects
     * that as a no-op user error. Duplicate entries in the request
     * are collapsed by the Set semantics.
     */
    private Set<EdZone> additionalZones;

    @NotNull(message = "Shift function is required")
    private ShiftFunction shiftFunction;

    /**
     * Whether this assignment should also carry the shift-lead badge.
     * Optional — defaults to false. Setting it true transfers the badge to
     * this user and clears it from any other holder for the same shift.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isShiftLead")
    private Boolean isShiftLead;

    /**
     * Target shift date. Optional — when null, the server uses
     * {@code getCurrentShiftDate()} (today, with the night-shift-after-
     * midnight roll-back the existing helper applies).
     *
     * <p>Must be {@code >= today (Africa/Kigali)} when set. Past dates
     * are rejected; see class Javadoc.
     */
    private LocalDate shiftDate;

    /**
     * Target shift period (DAY / NIGHT). Optional — when null, the server
     * uses {@code getCurrentShiftPeriod()}. If {@code shiftDate} is set
     * but {@code shiftPeriod} is not (or vice-versa), the request is
     * rejected: targeting a specific date without a period is ambiguous
     * (most calendar dates have both a DAY and a NIGHT shift).
     */
    private ShiftPeriod shiftPeriod;
}
