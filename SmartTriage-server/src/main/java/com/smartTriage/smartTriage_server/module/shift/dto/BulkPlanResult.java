package com.smartTriage.smartTriage_server.module.shift.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Summary returned by bulk shift-planning operations
 * ({@code copyWeek} / {@code applyTemplate}).
 *
 * <p>The frontend uses this to render a "12 of 14 shift slots filled, 2
 * skipped (already had a roster)" toast — partial success is the normal
 * case for a bulk operation in a life-critical system: a CN may have
 * already hand-edited Wednesday's roster, and copy-week must not silently
 * overwrite that work. Reporting per-slot outcomes lets the UI show
 * exactly what happened.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkPlanResult {

    /** Number of (date, period) slots where rows were materialised. */
    private int slotsFilled;

    /** Number of (date, period) slots skipped because they already had rows. */
    private int slotsSkipped;

    /** Total ShiftAssignment rows created across all slots. */
    private int rowsCreated;

    /**
     * Per-slot detail (one entry per (date, period) considered). Lets the
     * frontend show "Wed DAY: 6 created, Thu DAY: skipped (already filled)".
     */
    @Builder.Default
    private List<SlotOutcome> slots = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SlotOutcome {
        private LocalDate date;
        private String period; // "DAY" / "NIGHT"
        /** "FILLED", "SKIPPED_EXISTING", "SKIPPED_NO_SOURCE" */
        private String status;
        private int rowsCreated;
        private String note;
    }
}
