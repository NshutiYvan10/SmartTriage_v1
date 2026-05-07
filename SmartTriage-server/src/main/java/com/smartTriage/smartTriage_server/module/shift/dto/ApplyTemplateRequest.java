package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/shifts/hospital/{hospitalId}/apply-template}.
 *
 * <p>For every (date, period) tuple in the cartesian product of
 * [{@code fromDate} .. {@code toDate}] x {@code periods}, materialise the
 * named template into concrete {@link com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment}
 * rows — same code path the daily scheduler uses, just CN-triggered ahead of
 * time.
 *
 * <p>Idempotency: any (date, period) that already has active assignments is
 * skipped. The response reports how many slots actually materialised vs were
 * skipped.
 *
 * <p>Date guards: {@code fromDate} must not be in the past, and {@code toDate}
 * must not precede {@code fromDate}. Both validated server-side.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplyTemplateRequest {

    @NotNull(message = "templateId is required")
    private UUID templateId;

    @NotNull(message = "fromDate is required")
    private LocalDate fromDate;

    @NotNull(message = "toDate is required")
    private LocalDate toDate;

    /**
     * Which periods of each day to apply the template to. Usually the
     * template's own period (DAY templates apply to DAY shifts) — supplying
     * the wrong period is rejected server-side because materialising a
     * NIGHT template onto a DAY slot would give wrong staffing for that
     * shift's hours.
     */
    @NotEmpty(message = "at least one period must be selected")
    private List<ShiftPeriod> periods;
}
