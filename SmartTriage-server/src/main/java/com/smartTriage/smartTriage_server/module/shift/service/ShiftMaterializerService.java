package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplate;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplateAssignment;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftAssignmentRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Materializes reusable {@link ShiftTemplate}s into concrete
 * {@link ShiftAssignment} rows at a shift boundary.
 *
 * <h3>Semantics</h3>
 * <ul>
 *   <li><b>Idempotent</b>: running the materializer for a (hospital, date,
 *       period) that already has any active assignments is a no-op.
 *       Operators can safely re-run on startup, after a restart, or manually.</li>
 *   <li><b>Template-first</b>: if an active template exists for
 *       (hospital, period), it is used as the source of truth.</li>
 *   <li><b>Carry-over fallback</b>: if no template exists, the previous
 *       shift's roster at the same hospital is copied forward. This is what
 *       keeps the system functional for hospitals that have never defined
 *       a template — nothing is "empty" at shift boundary.</li>
 *   <li><b>No-op</b>: if neither a template nor a previous shift exists
 *       (brand-new tenant), nothing is materialized. Hospital Admin can
 *       define a template at any point.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftMaterializerService {

    private final ShiftTemplateRepository shiftTemplateRepository;
    private final ShiftAssignmentRepository shiftAssignmentRepository;

    /**
     * Materialize the current shift for a single hospital.
     *
     * @return the number of {@link ShiftAssignment} rows created (0 if
     *         already materialized or nothing to copy).
     */
    @Transactional
    public int materializeShift(Hospital hospital, LocalDate shiftDate, ShiftPeriod shiftPeriod) {
        // Idempotency guard: if anything is already active for this shift, bail.
        List<ShiftAssignment> existing = shiftAssignmentRepository
                .findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                        hospital.getId(), shiftDate, shiftPeriod);
        if (!existing.isEmpty()) {
            log.debug("Shift already materialized for {} on {} {} ({} rows) — skipping",
                    hospital.getHospitalCode(), shiftDate, shiftPeriod, existing.size());
            return 0;
        }

        // 1) Template-first path.
        Optional<ShiftTemplate> template = shiftTemplateRepository
                .findByHospitalIdAndShiftPeriodAndIsActiveTrue(hospital.getId(), shiftPeriod);
        if (template.isPresent()) {
            int created = materializeFromTemplate(template.get(), hospital, shiftDate, shiftPeriod);
            log.info("Materialized {} shift rows from template for {} {} {}",
                    created, hospital.getHospitalCode(), shiftDate, shiftPeriod);
            return created;
        }

        // 2) Carry-over fallback: copy the most recent previous shift.
        int copied = materializeFromPreviousShift(hospital, shiftDate, shiftPeriod);
        if (copied > 0) {
            log.info("Carried over {} rows from previous shift for {} {} {}",
                    copied, hospital.getHospitalCode(), shiftDate, shiftPeriod);
        } else {
            log.info("No template and no previous shift to carry over for {} {} {} — empty shift",
                    hospital.getHospitalCode(), shiftDate, shiftPeriod);
        }
        return copied;
    }

    private int materializeFromTemplate(
            ShiftTemplate template, Hospital hospital, LocalDate shiftDate, ShiftPeriod shiftPeriod) {
        Instant now = Instant.now();
        int created = 0;
        for (ShiftTemplateAssignment row : template.getAssignments()) {
            if (!row.isActive()) {
                continue;
            }
            // Skip if this specific user already has an active assignment on
            // this shift (e.g. they self-assigned before the materializer ran).
            boolean alreadyAssigned = shiftAssignmentRepository
                    .existsByUserIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                            row.getUser().getId(), shiftDate, shiftPeriod);
            if (alreadyAssigned) {
                continue;
            }

            ShiftAssignment sa = ShiftAssignment.builder()
                    .hospital(hospital)
                    .shiftDate(shiftDate)
                    .shiftPeriod(shiftPeriod)
                    .user(row.getUser())
                    .zone(row.getZone())
                    .shiftFunction(row.getShiftFunction())
                    .isShiftLead(row.isShiftLead())
                    .startedAt(now)
                    .build();
            shiftAssignmentRepository.save(sa);
            created++;
        }
        return created;
    }

    private int materializeFromPreviousShift(
            Hospital hospital, LocalDate shiftDate, ShiftPeriod shiftPeriod) {
        // The "previous shift" is the other period on the same day (or the
        // night-shift from yesterday if we're materializing a morning DAY).
        LocalDate prevDate;
        ShiftPeriod prevPeriod;
        if (shiftPeriod == ShiftPeriod.DAY) {
            prevDate = shiftDate.minusDays(1);
            prevPeriod = ShiftPeriod.NIGHT;
        } else {
            prevDate = shiftDate;
            prevPeriod = ShiftPeriod.DAY;
        }

        List<ShiftAssignment> prev = shiftAssignmentRepository
                .findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrueOrderByZone(
                        hospital.getId(), prevDate, prevPeriod);
        if (prev.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        int created = 0;
        for (ShiftAssignment src : prev) {
            boolean alreadyAssigned = shiftAssignmentRepository
                    .existsByUserIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                            src.getUser().getId(), shiftDate, shiftPeriod);
            if (alreadyAssigned) {
                continue;
            }
            ShiftAssignment copy = ShiftAssignment.builder()
                    .hospital(hospital)
                    .shiftDate(shiftDate)
                    .shiftPeriod(shiftPeriod)
                    .user(src.getUser())
                    .zone(src.getZone())
                    .shiftFunction(src.getShiftFunction())
                    .isShiftLead(src.isShiftLead())
                    .startedAt(now)
                    .build();
            shiftAssignmentRepository.save(copy);
            created++;
        }
        return created;
    }
}
