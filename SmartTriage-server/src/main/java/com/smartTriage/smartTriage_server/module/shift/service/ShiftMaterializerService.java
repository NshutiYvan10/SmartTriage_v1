package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplate;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplateAssignment;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftAssignmentRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftTemplateRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.StaffLeaveRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 *   <li><b>Leave-aware (V44+)</b>: any user whose approved leave covers
 *       this shift's date is dropped from the materialised roster, even if
 *       the template still names them. Prevents the "phantom shift-lead"
 *       failure where a CN on leave is auto-assigned the badge.</li>
 *   <li><b>Auto-promotion (V44+)</b>: after the roster is materialised, if
 *       no row carries {@code isShiftLead=true}, the highest-seniority
 *       NURSE on the roster is promoted to acting shift-lead. Seniority
 *       order: CHARGE_NURSE → SENIOR_NURSE → TRIAGE_NURSE → STAFF_NURSE
 *       → STUDENT_NURSE; ties broken by oldest user (longest tenure).
 *       Prevents the "no one has authority" failure when both day and
 *       night CNs are absent and no admin is on duty.</li>
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
    private final StaffLeaveRepository staffLeaveRepository;

    /**
     * Designation-based seniority used for auto-promotion to acting
     * shift-lead. Lower number = more senior. Anyone not in the map
     * (or null designation) gets a sentinel high value so they're
     * picked last. The designation enum is the source of truth — this
     * only orders them. Triage is a ShiftFunction, not a designation
     * (see V45), so it doesn't appear here.
     */
    private static final Map<Designation, Integer> NURSE_SENIORITY = Map.of(
            Designation.CHARGE_NURSE,  0,
            Designation.SENIOR_NURSE,  1,
            Designation.STAFF_NURSE,   2,
            Designation.STUDENT_NURSE, 3
    );

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
        int created;
        if (template.isPresent()) {
            created = materializeFromTemplate(template.get(), hospital, shiftDate, shiftPeriod);
            log.info("Materialized {} shift rows from template for {} {} {}",
                    created, hospital.getHospitalCode(), shiftDate, shiftPeriod);
        } else {
            // 2) Carry-over fallback: copy the most recent previous shift.
            created = materializeFromPreviousShift(hospital, shiftDate, shiftPeriod);
            if (created > 0) {
                log.info("Carried over {} rows from previous shift for {} {} {}",
                        created, hospital.getHospitalCode(), shiftDate, shiftPeriod);
            } else {
                log.info("No template and no previous shift to carry over for {} {} {} — empty shift",
                        hospital.getHospitalCode(), shiftDate, shiftPeriod);
                return 0;
            }
        }

        // 3) Auto-promotion: ensure the materialised shift has a shift-lead.
        // If the template/carryover left the badge with someone who got
        // skipped (on leave) or simply unset, promote the most senior nurse
        // on the new roster. This is the safeguard for the "both CNs absent,
        // no admin on duty" scenario — the floor always has SOMEONE with
        // shift-lead authority.
        ensureActingShiftLead(hospital, shiftDate, shiftPeriod);

        return created;
    }

    /**
     * Materialise a specific named template into one (date, period) slot.
     * Used by the planning bulk-ops path (apply-template) where the CN
     * picks the exact template — distinct from {@link #materializeShift}
     * which always uses the active template for the period.
     *
     * <p>Idempotency: if the slot already has any active assignments, this
     * method is a no-op and returns 0. Same leave-aware + auto-promote
     * guarantees as the daily scheduler.
     *
     * @return rows created (0 if slot was already populated).
     */
    @Transactional
    public int materializeFromTemplateExplicit(
            ShiftTemplate template, Hospital hospital,
            LocalDate shiftDate, ShiftPeriod shiftPeriod) {
        List<ShiftAssignment> existing = shiftAssignmentRepository
                .findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                        hospital.getId(), shiftDate, shiftPeriod);
        if (!existing.isEmpty()) {
            return 0;
        }
        int created = materializeFromTemplate(template, hospital, shiftDate, shiftPeriod);
        ensureActingShiftLead(hospital, shiftDate, shiftPeriod);
        return created;
    }

    /**
     * Run only the auto-promotion step on an already-materialised shift.
     * Used by bulk-ops (e.g. copy-week) after they bulk-insert rows
     * directly: the rows didn't go through {@link #materializeShift}, so
     * the auto-promote safeguard hasn't run yet.
     */
    @Transactional
    public void ensureActingShiftLeadPublic(Hospital hospital, LocalDate shiftDate, ShiftPeriod shiftPeriod) {
        ensureActingShiftLead(hospital, shiftDate, shiftPeriod);
    }

    /**
     * True when the user has approved leave covering this date. Exposed for
     * planning services that copy/apply rows outside the materialiser's
     * normal entry points and need the same leave-aware filter.
     */
    public boolean isOnApprovedLeavePublic(java.util.UUID userId, LocalDate shiftDate) {
        return isOnApprovedLeave(userId, shiftDate);
    }

    private int materializeFromTemplate(
            ShiftTemplate template, Hospital hospital, LocalDate shiftDate, ShiftPeriod shiftPeriod) {
        Instant now = Instant.now();
        int created = 0;
        int skippedOnLeave = 0;
        for (ShiftTemplateAssignment row : template.getAssignments()) {
            if (!row.isActive()) {
                continue;
            }
            User u = row.getUser();
            if (u == null) {
                continue;
            }

            // V44+ leave-aware: drop anyone whose approved leave covers
            // this shift's date. The template stays as written; only the
            // materialised roster excludes them. Prevents the "phantom
            // shift-lead" case where the template names a CN who's on
            // approved leave.
            if (isOnApprovedLeave(u.getId(), shiftDate)) {
                log.debug("[materializer] Skipping {} (on approved leave) for {} {} {}",
                        u.getEmail(), hospital.getHospitalCode(), shiftDate, shiftPeriod);
                skippedOnLeave++;
                continue;
            }

            // Skip if this specific user already has an active assignment on
            // this shift (e.g. they self-assigned before the materializer ran).
            boolean alreadyAssigned = shiftAssignmentRepository
                    .existsByUserIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                            u.getId(), shiftDate, shiftPeriod);
            if (alreadyAssigned) {
                continue;
            }

            ShiftAssignment sa = ShiftAssignment.builder()
                    .hospital(hospital)
                    .shiftDate(shiftDate)
                    .shiftPeriod(shiftPeriod)
                    .user(u)
                    .zone(row.getZone())
                    .shiftFunction(row.getShiftFunction())
                    .isShiftLead(row.isShiftLead())
                    .startedAt(now)
                    .build();
            shiftAssignmentRepository.save(sa);
            created++;
        }
        if (skippedOnLeave > 0) {
            log.info("[materializer] Skipped {} template entries on approved leave for {} {} {}",
                    skippedOnLeave, hospital.getHospitalCode(), shiftDate, shiftPeriod);
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
        int skippedOnLeave = 0;
        for (ShiftAssignment src : prev) {
            User u = src.getUser();
            if (u == null) {
                continue;
            }

            // V44+ leave-aware (same rationale as the template path).
            if (isOnApprovedLeave(u.getId(), shiftDate)) {
                log.debug("[materializer] Carry-over: skipping {} (on approved leave)",
                        u.getEmail());
                skippedOnLeave++;
                continue;
            }

            boolean alreadyAssigned = shiftAssignmentRepository
                    .existsByUserIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                            u.getId(), shiftDate, shiftPeriod);
            if (alreadyAssigned) {
                continue;
            }
            ShiftAssignment copy = ShiftAssignment.builder()
                    .hospital(hospital)
                    .shiftDate(shiftDate)
                    .shiftPeriod(shiftPeriod)
                    .user(u)
                    .zone(src.getZone())
                    .shiftFunction(src.getShiftFunction())
                    .isShiftLead(src.isShiftLead())
                    .startedAt(now)
                    .build();
            shiftAssignmentRepository.save(copy);
            created++;
        }
        if (skippedOnLeave > 0) {
            log.info("[materializer] Carry-over: skipped {} entries on approved leave for {} {} {}",
                    skippedOnLeave, hospital.getHospitalCode(), shiftDate, shiftPeriod);
        }
        return created;
    }

    /**
     * If the freshly-materialised shift has no row with isShiftLead=true,
     * promote the most senior NURSE on the roster. The clinical reasoning:
     * a real charge nurse on a real ED floor would never let a shift run
     * with nobody in charge — somebody always steps up. The system mirrors
     * that: the senior person on shift gets the badge automatically.
     *
     * <p>Seniority order:
     * CHARGE_NURSE → SENIOR_NURSE → TRIAGE_NURSE → STAFF_NURSE → STUDENT_NURSE.
     * Tie-broken by oldest user (longest tenure at the hospital), which
     * is a stable proxy for institutional knowledge.
     *
     * <p>If no NURSE-role staff exists on the roster (e.g. only doctors
     * and lab techs), the badge stays unset — the floor falls back to
     * HOSPITAL_ADMIN authority via {@code ShiftAssignmentAuthz.canAssign}.
     */
    private void ensureActingShiftLead(Hospital hospital, LocalDate shiftDate, ShiftPeriod shiftPeriod) {
        List<ShiftAssignment> roster = shiftAssignmentRepository
                .findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                        hospital.getId(), shiftDate, shiftPeriod);
        if (roster.isEmpty()) {
            return;
        }

        boolean alreadyHasLead = roster.stream().anyMatch(ShiftAssignment::isShiftLead);
        if (alreadyHasLead) {
            return;
        }

        Optional<ShiftAssignment> mostSenior = roster.stream()
                .filter(sa -> sa.getUser() != null && sa.getUser().getRole() == Role.NURSE)
                .min(Comparator
                        .<ShiftAssignment>comparingInt(sa -> seniorityRank(sa.getUser().getDesignation()))
                        .thenComparing(sa -> sa.getUser().getCreatedAt() != null
                                ? sa.getUser().getCreatedAt()
                                : Instant.MAX));

        if (mostSenior.isEmpty()) {
            log.warn("[materializer] No NURSE-role staff on roster for {} {} {} — "
                            + "shift-lead badge unset, falls back to HOSPITAL_ADMIN authority",
                    hospital.getHospitalCode(), shiftDate, shiftPeriod);
            return;
        }

        ShiftAssignment promoted = mostSenior.get();
        promoted.setShiftLead(true);
        shiftAssignmentRepository.save(promoted);

        Designation d = promoted.getUser().getDesignation();
        log.info("[materializer] AUTO-PROMOTED {} ({}) to acting shift-lead for {} {} {} "
                        + "(no shift-lead nominated by template/carryover)",
                promoted.getUser().getEmail(),
                d != null ? d.name() : "no-designation",
                hospital.getHospitalCode(), shiftDate, shiftPeriod);
    }

    /** True if this user is on approved leave covering the given shift date. */
    private boolean isOnApprovedLeave(java.util.UUID userId, LocalDate shiftDate) {
        return !staffLeaveRepository.findApprovedCovering(userId, shiftDate).isEmpty();
    }

    private static int seniorityRank(Designation d) {
        return NURSE_SENIORITY.getOrDefault(d, Integer.MAX_VALUE);
    }
}
