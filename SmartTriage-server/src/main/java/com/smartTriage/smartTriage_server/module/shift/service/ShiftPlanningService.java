package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.shift.dto.ApplyTemplateRequest;
import com.smartTriage.smartTriage_server.module.shift.dto.BulkPlanResult;
import com.smartTriage.smartTriage_server.module.shift.dto.CopyWeekRequest;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplate;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftAssignmentRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk shift-planning operations.
 *
 * <p>Sits one layer above {@link ShiftAssignmentService} (single-row writes)
 * and {@link ShiftMaterializerService} (single-slot template materialisation).
 * Provides the two charge-nurse-driven bulk operations exposed in the
 * Shift Calendar UI:
 *
 * <ul>
 *   <li><b>copy-week</b>: replicate one week's roster into another week,
 *       preserving (zone, function, period, isShiftLead, day-offset).
 *       The bread-and-butter of weekly scheduling — once a CN has built
 *       a "good week", they reuse it.</li>
 *   <li><b>apply-template</b>: materialise a specific template across a
 *       date range. Useful after a template edit when the CN wants the
 *       upcoming N days to reflect the new layout immediately, instead
 *       of waiting for the daily scheduler to roll over.</li>
 * </ul>
 *
 * <h3>Safety properties shared by both ops</h3>
 * <ul>
 *   <li><b>Idempotent per slot</b>: any (date, period) that already has
 *       active assignments is skipped — a CN cannot accidentally clobber
 *       a hand-edited roster with a bulk paste.</li>
 *   <li><b>Leave-aware</b>: a user with approved leave covering the target
 *       date is dropped from the materialised row set.</li>
 *   <li><b>Auto-promote</b>: every freshly-filled slot runs
 *       {@link ShiftMaterializerService#ensureActingShiftLeadPublic} so no
 *       shift ends up without someone holding the shift-lead badge.</li>
 *   <li><b>Past-date guard</b>: every (date, period) before today
 *       (Africa/Kigali) is rejected up-front. Editing past rosters would
 *       corrupt the audit trail for any clinical actions logged against
 *       that shift.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftPlanningService {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    private final HospitalRepository hospitalRepository;
    private final ShiftTemplateRepository shiftTemplateRepository;
    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final ShiftMaterializerService shiftMaterializerService;

    /* ════════════════════════════ COPY-WEEK ════════════════════════════ */

    /**
     * Copy every active assignment row from the source week into the
     * target week, preserving (zone, function, period, day-offset).
     *
     * <p>Both dates must be Mondays. Validation is strict — silently
     * rounding to a Monday would let a CN accidentally copy a 6-day
     * window without realising it.
     */
    @Transactional
    public BulkPlanResult copyWeek(UUID hospitalId, CopyWeekRequest req) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        LocalDate fromMonday = req.getFromWeekStart();
        LocalDate toMonday = req.getToWeekStart();

        if (fromMonday.getDayOfWeek() != DayOfWeek.MONDAY
                || toMonday.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new ClinicalBusinessException(
                    "fromWeekStart and toWeekStart must both be Mondays. "
                            + "Got fromWeekStart=" + fromMonday.getDayOfWeek()
                            + ", toWeekStart=" + toMonday.getDayOfWeek() + ".");
        }
        if (fromMonday.equals(toMonday)) {
            throw new ClinicalBusinessException(
                    "fromWeekStart and toWeekStart cannot be the same week.");
        }

        LocalDate today = LocalDate.now(KIGALI);
        if (toMonday.isBefore(today)) {
            throw new ClinicalBusinessException(
                    "Target week (" + toMonday + ") is in the past. Past rosters are read-only.");
        }

        // Source rows for the entire 7-day window, all periods.
        LocalDate fromSunday = fromMonday.plusDays(6);
        List<ShiftAssignment> sourceRows = shiftAssignmentRepository
                .findByHospitalIdAndShiftDateBetweenAndIsActiveTrue(
                        hospitalId, fromMonday, fromSunday);

        if (sourceRows.isEmpty()) {
            log.info("[copy-week] Source week {} has no active rows — nothing to copy.", fromMonday);
            return BulkPlanResult.builder()
                    .slotsFilled(0).slotsSkipped(0).rowsCreated(0)
                    .build();
        }

        // Group source rows by (offset, period) so we can examine each
        // target slot atomically.
        Map<SlotKey, List<ShiftAssignment>> bySlot = new HashMap<>();
        for (ShiftAssignment sa : sourceRows) {
            int offset = (int) java.time.temporal.ChronoUnit.DAYS.between(fromMonday, sa.getShiftDate());
            if (offset < 0 || offset > 6) {
                continue; // safety
            }
            bySlot.computeIfAbsent(new SlotKey(offset, sa.getShiftPeriod()),
                    k -> new java.util.ArrayList<>()).add(sa);
        }

        BulkPlanResult result = BulkPlanResult.builder().build();
        Instant now = Instant.now();

        for (Map.Entry<SlotKey, List<ShiftAssignment>> entry : bySlot.entrySet()) {
            int offset = entry.getKey().offset();
            ShiftPeriod period = entry.getKey().period();
            LocalDate targetDate = toMonday.plusDays(offset);

            BulkPlanResult.SlotOutcome outcome = BulkPlanResult.SlotOutcome.builder()
                    .date(targetDate).period(period.name())
                    .build();

            // Past-date guard, evaluated per slot — copying into a week
            // that straddles today is allowed, but the past dates inside
            // it are skipped (not failed) so the CN gets a clear partial
            // result.
            if (targetDate.isBefore(today)) {
                outcome.setStatus("SKIPPED_EXISTING");
                outcome.setNote("Target date is in the past; skipped.");
                result.getSlots().add(outcome);
                result.setSlotsSkipped(result.getSlotsSkipped() + 1);
                continue;
            }

            // Idempotency: don't overwrite a hand-edited roster.
            List<ShiftAssignment> existing = shiftAssignmentRepository
                    .findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                            hospitalId, targetDate, period);
            if (!existing.isEmpty()) {
                outcome.setStatus("SKIPPED_EXISTING");
                outcome.setNote("Target slot already has " + existing.size() + " row(s).");
                result.getSlots().add(outcome);
                result.setSlotsSkipped(result.getSlotsSkipped() + 1);
                continue;
            }

            int created = 0;
            int skippedOnLeave = 0;
            for (ShiftAssignment src : entry.getValue()) {
                if (src.getUser() == null) continue;
                if (shiftMaterializerService.isOnApprovedLeavePublic(
                        src.getUser().getId(), targetDate)) {
                    skippedOnLeave++;
                    continue;
                }
                ShiftAssignment copy = ShiftAssignment.builder()
                        .hospital(hospital)
                        .shiftDate(targetDate)
                        .shiftPeriod(period)
                        .user(src.getUser())
                        .zone(src.getZone())
                        .shiftFunction(src.getShiftFunction())
                        .isShiftLead(src.isShiftLead())
                        .startedAt(now)
                        .build();
                shiftAssignmentRepository.save(copy);
                created++;
            }

            // Run auto-promote so every copied slot has a shift-lead even
            // if the source-week's lead happened to be on leave.
            shiftMaterializerService.ensureActingShiftLeadPublic(hospital, targetDate, period);

            outcome.setStatus("FILLED");
            outcome.setRowsCreated(created);
            if (skippedOnLeave > 0) {
                outcome.setNote(skippedOnLeave + " row(s) skipped — user on approved leave.");
            }
            result.getSlots().add(outcome);
            result.setSlotsFilled(result.getSlotsFilled() + 1);
            result.setRowsCreated(result.getRowsCreated() + created);
        }

        log.info("[copy-week] {} → {} : {} slots filled, {} skipped, {} rows created",
                fromMonday, toMonday,
                result.getSlotsFilled(), result.getSlotsSkipped(), result.getRowsCreated());
        return result;
    }

    /* ══════════════════════════ APPLY-TEMPLATE ══════════════════════════ */

    @Transactional
    public BulkPlanResult applyTemplate(UUID hospitalId, ApplyTemplateRequest req) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        ShiftTemplate template = shiftTemplateRepository.findByIdAndIsActiveTrue(req.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ShiftTemplate", "id", req.getTemplateId()));

        if (!template.getHospital().getId().equals(hospitalId)) {
            throw new ClinicalBusinessException(
                    "Template " + req.getTemplateId() + " does not belong to hospital " + hospitalId);
        }
        if (req.getFromDate().isAfter(req.getToDate())) {
            throw new ClinicalBusinessException(
                    "fromDate (" + req.getFromDate() + ") must not be after toDate (" + req.getToDate() + ").");
        }
        LocalDate today = LocalDate.now(KIGALI);
        if (req.getFromDate().isBefore(today)) {
            throw new ClinicalBusinessException(
                    "fromDate (" + req.getFromDate() + ") is in the past. "
                            + "Past rosters are read-only.");
        }

        // The template knows its own period. Reject any selected period that
        // doesn't match — applying a NIGHT template to a DAY shift would put
        // night-staff onto day hours.
        for (ShiftPeriod p : req.getPeriods()) {
            if (p != template.getShiftPeriod()) {
                throw new ClinicalBusinessException(
                        "Template " + template.getName() + " is a " + template.getShiftPeriod()
                                + " template; cannot apply to " + p + ". "
                                + "Pick a template whose period matches.");
            }
        }

        BulkPlanResult result = BulkPlanResult.builder().build();
        for (LocalDate d = req.getFromDate(); !d.isAfter(req.getToDate()); d = d.plusDays(1)) {
            for (ShiftPeriod period : req.getPeriods()) {
                BulkPlanResult.SlotOutcome outcome = BulkPlanResult.SlotOutcome.builder()
                        .date(d).period(period.name())
                        .build();

                int created = shiftMaterializerService
                        .materializeFromTemplateExplicit(template, hospital, d, period);
                if (created == 0) {
                    // Either slot already had rows, or the template rows
                    // were all on leave / already-assigned. Distinguish:
                    List<ShiftAssignment> existing = shiftAssignmentRepository
                            .findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                                    hospitalId, d, period);
                    if (!existing.isEmpty()) {
                        outcome.setStatus("SKIPPED_EXISTING");
                        outcome.setNote("Slot already had " + existing.size() + " row(s).");
                    } else {
                        outcome.setStatus("SKIPPED_NO_SOURCE");
                        outcome.setNote("Template materialised 0 rows (all on leave or empty).");
                    }
                    result.setSlotsSkipped(result.getSlotsSkipped() + 1);
                } else {
                    outcome.setStatus("FILLED");
                    outcome.setRowsCreated(created);
                    result.setSlotsFilled(result.getSlotsFilled() + 1);
                    result.setRowsCreated(result.getRowsCreated() + created);
                }
                result.getSlots().add(outcome);
            }
        }

        log.info("[apply-template] template={} range {}..{} periods={} : {} filled / {} skipped / {} rows",
                template.getName(), req.getFromDate(), req.getToDate(), req.getPeriods(),
                result.getSlotsFilled(), result.getSlotsSkipped(), result.getRowsCreated());
        return result;
    }

    /** Composite key for grouping copy-week source rows. */
    private record SlotKey(int offset, ShiftPeriod period) {}
}
