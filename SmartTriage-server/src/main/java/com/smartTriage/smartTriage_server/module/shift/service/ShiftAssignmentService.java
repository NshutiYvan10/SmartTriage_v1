package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.shift.dto.CreateShiftAssignmentRequest;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftAssignmentResponse;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;
import com.smartTriage.smartTriage_server.module.shift.mapper.ShiftAssignmentMapper;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftAssignmentRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.StaffLeaveRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shift Assignment Service — manages zone-based staff assignments per shift.
 *
 * <p>The Rwandan EDs SmartTriage targets (KFH, CHUK, RMH, district hospitals)
 * operate a 2-shift roster — see {@link ShiftPeriod}:
 * <ul>
 *   <li>{@code DAY}   — 07:00 – 19:00</li>
 *   <li>{@code NIGHT} — 19:00 – 07:00 (crosses midnight)</li>
 * </ul>
 *
 * <p>The Charge Nurse assigns doctors and nurses to ED zones (RESUS, ACUTE,
 * GENERAL, AMBULATORY, PEDIATRIC, NEONATAL, ISOLATION, OBSERVATION) at the
 * start of each shift. This mapping drives alert routing and per-user
 * patient-list scoping.
 *
 * <p>Authority for staffing decisions is checked by
 * {@link ShiftAssignmentAuthz#canAssign}; the on-duty Charge Nurse (or an
 * acting CN per {@link com.smartTriage.smartTriage_server.module.shift.entity.ChargeNurseDelegation})
 * is the primary actor, with HOSPITAL_ADMIN and SUPER_ADMIN as fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftAssignmentService {

    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final UserRepository userRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffLeaveRepository staffLeaveRepository;

    /**
     * Determine the current shift period based on current time.
     * DAY: 07:00 – 18:59
     * NIGHT: 19:00 – 06:59
     */
    public static ShiftPeriod getCurrentShiftPeriod() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(7, 0))) {
            return ShiftPeriod.NIGHT;
        } else if (now.isBefore(LocalTime.of(19, 0))) {
            return ShiftPeriod.DAY;
        } else {
            return ShiftPeriod.NIGHT;
        }
    }

    /**
     * Get the shift date for the current shift (night shift before midnight uses
     * today's date;
     * night shift after midnight uses yesterday's date to match the shift that
     * started the evening before).
     */
    public static LocalDate getCurrentShiftDate() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(7, 0))) {
            // After midnight but before 07:00 — this night shift started yesterday
            return LocalDate.now().minusDays(1);
        }
        return LocalDate.now();
    }

    /**
     * Assign a staff member to a zone for a specific shift.
     *
     * <p>If the request omits {@code shiftDate} and {@code shiftPeriod}, the
     * assignment lands on the current shift (today's DAY or NIGHT, computed
     * via {@link #getCurrentShiftDate()} / {@link #getCurrentShiftPeriod()}).
     * If both are provided, the assignment targets that specific shift —
     * this is how the calendar's quick-assign drawer schedules a future
     * date from inside today's UI.
     *
     * <p>Server-side guards (in this order):
     * <ol>
     *   <li>If exactly one of {@code shiftDate} / {@code shiftPeriod} is set,
     *       reject — both must be set together or both omitted.</li>
     *   <li>{@code shiftDate} must not be in the past (Africa/Kigali).
     *       Backdating roster history would corrupt audit trails.</li>
     *   <li>The user cannot be on approved leave that covers
     *       {@code shiftDate}. Cross-checked against
     *       {@link StaffLeaveRepository#findApprovedCovering}.</li>
     * </ol>
     *
     * <p>Conflict handling: if the user is already on the active roster
     * for the same (date, period), the existing row is soft-ended before
     * the new one is inserted — same behaviour as the today-only path.
     * If the request sets {@code isShiftLead=true}, the existing badge
     * holder for that shift is cleared first to satisfy the partial
     * unique index.
     */
    @Transactional
    public ShiftAssignmentResponse assignToZone(UUID hospitalId, CreateShiftAssignmentRequest request) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        User user = userRepository.findByIdAndIsActiveTrue(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.getUserId()));

        LocalDate shiftDate;
        ShiftPeriod shiftPeriod;

        // Both date and period must be specified together — targeting one
        // without the other is ambiguous.
        boolean hasDate = request.getShiftDate() != null;
        boolean hasPeriod = request.getShiftPeriod() != null;
        if (hasDate ^ hasPeriod) {
            throw new ClinicalBusinessException(
                    "shiftDate and shiftPeriod must be provided together. "
                            + "Either set both for a specific future shift, or omit both "
                            + "for today's current shift.");
        }

        if (hasDate) {
            shiftDate = request.getShiftDate();
            shiftPeriod = request.getShiftPeriod();

            // Past-date guard. Africa/Kigali is the operational time zone.
            // We compare against today rather than getCurrentShiftDate() —
            // the night-shift-after-midnight roll-back applies only to
            // "what shift am I on RIGHT NOW", not to "what dates can I
            // schedule for". A CN at 02:00 should still be able to
            // schedule the upcoming Wednesday.
            LocalDate todayKigali = LocalDate.now(ZoneId.of("Africa/Kigali"));
            if (shiftDate.isBefore(todayKigali)) {
                throw new ClinicalBusinessException(
                        "Cannot schedule a shift assignment for a past date ("
                                + shiftDate + "). Past rosters are read-only — "
                                + "edits would corrupt the clinical-action audit trail.");
            }
        } else {
            shiftDate = getCurrentShiftDate();
            shiftPeriod = getCurrentShiftPeriod();
        }

        // Leave-aware guard. A user with approved leave covering the target
        // date cannot be scheduled on that date — the materialiser already
        // skips them at daily roll-over (Fix #2 from the absence audit);
        // the manual scheduling path enforces the same rule so a CN can't
        // accidentally re-introduce the conflict via the calendar UI.
        if (!staffLeaveRepository.findApprovedCovering(user.getId(), shiftDate).isEmpty()) {
            throw new ClinicalBusinessException(
                    user.getEmail() + " has approved leave covering "
                            + shiftDate + " and cannot be scheduled on that date. "
                            + "Cancel the leave first or pick a different date.");
        }

        // Deactivate any existing assignment for this user on this shift —
        // matches the historical behaviour of the today-only path.
        shiftAssignmentRepository.findByUserIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
                user.getId(), shiftDate, shiftPeriod).ifPresent(existing -> {
                    existing.setActive(false);
                    existing.setEndedAt(Instant.now());
                    shiftAssignmentRepository.save(existing);
                    log.info("Deactivated previous assignment for user {} on {} {}",
                            user.getEmail(), shiftDate, shiftPeriod);
                });

        boolean makeShiftLead = Boolean.TRUE.equals(request.getIsShiftLead());

        // If this user is going to be the shift-lead, clear any other
        // active lead for the same (hospital, shiftDate, shiftPeriod) — the
        // partial unique index would otherwise reject the insert.
        if (makeShiftLead) {
            clearShiftLeadForShift(hospitalId, shiftDate, shiftPeriod);
        }

        ShiftAssignment assignment = ShiftAssignment.builder()
                .hospital(hospital)
                .shiftDate(shiftDate)
                .shiftPeriod(shiftPeriod)
                .user(user)
                .zone(request.getZone())
                .shiftFunction(request.getShiftFunction())
                .startedAt(Instant.now())
                .isShiftLead(makeShiftLead)
                .build();

        assignment = shiftAssignmentRepository.save(assignment);
        log.info("Shift assignment created: {} → {} zone, function {}{} on {} {}",
                user.getEmail(), request.getZone(), request.getShiftFunction(),
                makeShiftLead ? " [SHIFT-LEAD]" : "", shiftDate, shiftPeriod);

        return ShiftAssignmentMapper.toResponse(assignment);
    }

    /**
     * Clear the shift-lead flag from any active assignment for the given
     * (hospital, shiftDate, shiftPeriod). Called before setting a new lead
     * so the partial unique index is never violated.
     */
    @Transactional
    public void clearShiftLeadForShift(UUID hospitalId, LocalDate shiftDate, ShiftPeriod shiftPeriod) {
        shiftAssignmentRepository.findShiftLead(hospitalId, shiftDate, shiftPeriod)
                .ifPresent(prev -> {
                    prev.setShiftLead(false);
                    shiftAssignmentRepository.save(prev);
                    log.info("Cleared previous shift-lead badge from {} on {} {}",
                            prev.getUser().getEmail(), shiftDate, shiftPeriod);
                });
    }

    /**
     * Get all assignments for the current shift at a hospital.
     */
    public List<ShiftAssignmentResponse> getCurrentShiftAssignments(UUID hospitalId) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();

        return shiftAssignmentRepository
                .findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrue(hospitalId, shiftDate, shiftPeriod)
                .stream()
                .map(ShiftAssignmentMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get the primary doctor assigned to a specific zone for the current shift.
     * Used by the alert system to route notifications.
     */
    public List<User> getDoctorsForZone(UUID hospitalId, EdZone zone) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();

        return shiftAssignmentRepository
                .findByZoneAndFunction(hospitalId, shiftDate, shiftPeriod, zone, ShiftFunction.PRIMARY_DOCTOR)
                .stream()
                .map(ShiftAssignment::getUser)
                .collect(Collectors.toList());
    }

    /**
     * Get ALL doctors on duty (any zone) for the current shift.
     * Used for Tier 2 escalation — broadcast to all doctors.
     */
    public List<User> getAllDoctorsOnDuty(UUID hospitalId) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();

        return shiftAssignmentRepository
                .findAllDoctorsOnDuty(hospitalId, shiftDate, shiftPeriod)
                .stream()
                .map(ShiftAssignment::getUser)
                .collect(Collectors.toList());
    }

    /**
     * Get ALL staff on duty for the current shift.
     * Used for Tier 3 escalation — broadcast to everyone.
     */
    public List<ShiftAssignment> getAllStaffOnDuty(UUID hospitalId) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();

        return shiftAssignmentRepository
                .findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrueOrderByZone(
                        hospitalId, shiftDate, shiftPeriod);
    }

    /**
     * Phase 1 zone routing — return the active shift assignment for a
     * single user on the current shift period, or empty when they're
     * off-shift.
     *
     * <p>Drives the frontend's zone-scoped patient list: a user with
     * a specific zone sees only that zone; a shift lead sees all
     * zones; an off-shift user sees only patients they're explicitly
     * primary clinician on.
     */
    public Optional<ShiftAssignmentResponse> getCurrentShiftForUser(UUID userId) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();
        return shiftAssignmentRepository
                .findByUserIdAndShiftDateAndShiftPeriodAndIsActiveTrue(userId, shiftDate, shiftPeriod)
                .map(ShiftAssignmentMapper::toResponse);
    }

    /**
     * Get assignments for a specific zone on the current shift.
     */
    public List<ShiftAssignmentResponse> getZoneAssignments(UUID hospitalId, EdZone zone) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();

        return shiftAssignmentRepository
                .findByHospitalIdAndShiftDateAndShiftPeriodAndZoneAndIsActiveTrue(
                        hospitalId, shiftDate, shiftPeriod, zone)
                .stream()
                .map(ShiftAssignmentMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Remove a shift assignment (deactivate).
     */
    @Transactional
    public void removeAssignment(UUID assignmentId) {
        ShiftAssignment assignment = shiftAssignmentRepository.findByIdAndIsActiveTrue(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftAssignment", "id", assignmentId));
        assignment.setActive(false);
        shiftAssignmentRepository.save(assignment);
        log.info("Shift assignment removed: {}", assignmentId);
    }

    /**
     * Get zone staffing summary — used for surge detection.
     * Returns counts of staff per zone.
     */
    public List<Object[]> getZoneStaffingCounts(UUID hospitalId) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();

        return shiftAssignmentRepository.countStaffByZone(hospitalId, shiftDate, shiftPeriod);
    }

    /**
     * Get the charge nurse on duty for the current shift.
     * Used by Tier 1 escalation — notify the charge nurse immediately.
     */
    public List<User> getChargeNurse(UUID hospitalId) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();

        return shiftAssignmentRepository
                .findChargeNurse(hospitalId, shiftDate, shiftPeriod)
                .stream()
                .map(ShiftAssignment::getUser)
                .collect(Collectors.toList());
    }

    /**
     * Get all assignments for a specific date at a hospital (any shift period).
     * Used for shift history / reporting.
     */
    public List<ShiftAssignmentResponse> getShiftByDate(UUID hospitalId, LocalDate date) {
        return shiftAssignmentRepository
                .findByHospitalIdAndShiftDateAndIsActiveTrue(hospitalId, date)
                .stream()
                .map(ShiftAssignmentMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get active shift history for a specific user.
     */
    public List<ShiftAssignmentResponse> getUserShiftHistory(UUID userId) {
        return shiftAssignmentRepository
                .findByUserIdAndIsActiveTrueOrderByShiftDateDescShiftPeriodDesc(userId)
                .stream()
                .map(ShiftAssignmentMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * End a shift assignment (set endedAt + deactivate).
     */
    @Transactional
    public ShiftAssignmentResponse endShift(UUID assignmentId) {
        ShiftAssignment assignment = shiftAssignmentRepository.findByIdAndIsActiveTrue(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftAssignment", "id", assignmentId));
        assignment.setActive(false);
        assignment.setEndedAt(Instant.now());
        assignment = shiftAssignmentRepository.save(assignment);
        log.info("Shift assignment ended: {} for user {}", assignmentId, assignment.getUser().getEmail());
        return ShiftAssignmentMapper.toResponse(assignment);
    }

    /**
     * Update an existing shift assignment (change zone or function).
     */
    @Transactional
    public ShiftAssignmentResponse updateAssignment(UUID assignmentId, CreateShiftAssignmentRequest request) {
        ShiftAssignment assignment = shiftAssignmentRepository.findByIdAndIsActiveTrue(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftAssignment", "id", assignmentId));

        if (request.getZone() != null) {
            assignment.setZone(request.getZone());
        }
        if (request.getShiftFunction() != null) {
            assignment.setShiftFunction(request.getShiftFunction());
        }

        assignment = shiftAssignmentRepository.save(assignment);
        log.info("Shift assignment updated: {} → zone {}, function {}",
                assignmentId, assignment.getZone(), assignment.getShiftFunction());
        return ShiftAssignmentMapper.toResponse(assignment);
    }

    /**
     * Get staff assigned to a specific zone with a specific function.
     * Generic helper used when you need e.g. all ZONE_NURSEs in RESUS.
     */
    public List<User> getStaffByZoneAndFunction(UUID hospitalId, EdZone zone, ShiftFunction shiftFunction) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();

        return shiftAssignmentRepository
                .findByZoneAndFunction(hospitalId, shiftDate, shiftPeriod, zone, shiftFunction)
                .stream()
                .map(ShiftAssignment::getUser)
                .collect(Collectors.toList());
    }

    /* ══════════════════════ SHIFT-LEAD BADGE API ══════════════════════ */

    /** Grace window during which the previous shift's lead still has authority. */
    public static final Duration SHIFT_LEAD_GRACE = Duration.ofMinutes(30);

    /**
     * The shift-lead for the <em>current</em> shift at a hospital, if any.
     * Returns empty when nobody holds the badge yet (e.g. at the start of a
     * shift before anyone has checked in).
     */
    public Optional<ShiftAssignmentResponse> getCurrentShiftLead(UUID hospitalId) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();
        return shiftAssignmentRepository.findShiftLead(hospitalId, shiftDate, shiftPeriod)
                .map(ShiftAssignmentMapper::toResponse);
    }

    /**
     * Does the given user currently hold the shift-lead badge at this
     * hospital? Used by the permission evaluator.
     */
    public boolean isUserCurrentShiftLead(UUID userId, UUID hospitalId) {
        LocalDate shiftDate = getCurrentShiftDate();
        ShiftPeriod shiftPeriod = getCurrentShiftPeriod();
        return shiftAssignmentRepository.findShiftLead(hospitalId, shiftDate, shiftPeriod)
                .map(sa -> sa.getUser().getId().equals(userId))
                .orElse(false);
    }

    /**
     * Did the given user hold the shift-lead badge in the previous shift,
     * and are we still inside the {@link #SHIFT_LEAD_GRACE} window? This is
     * the fallback that prevents an authority gap at shift changeover when
     * the incoming lead hasn't clocked in yet.
     */
    public boolean isUserWithinShiftLeadGrace(UUID userId, UUID hospitalId) {
        List<ShiftAssignment> rows = shiftAssignmentRepository
                .findRecentShiftLeadRowsForUser(userId, hospitalId);
        Instant now = Instant.now();
        for (ShiftAssignment sa : rows) {
            Instant ended = sa.getEndedAt();
            if (ended == null) {
                // still active — handled elsewhere as the current lead
                continue;
            }
            if (Duration.between(ended, now).compareTo(SHIFT_LEAD_GRACE) <= 0) {
                return true;
            }
            // rows are ordered newest-first, so once we fall outside the
            // grace window we can stop.
            return false;
        }
        return false;
    }

    /**
     * Transfer the shift-lead badge to a specific existing assignment. Used
     * by an Admin or by the current lead to hand the hat over explicitly.
     */
    @Transactional
    public ShiftAssignmentResponse setShiftLead(UUID assignmentId) {
        ShiftAssignment target = shiftAssignmentRepository.findByIdAndIsActiveTrue(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftAssignment", "id", assignmentId));

        if (target.getHospital() == null) {
            throw new ClinicalBusinessException("Assignment is not attached to a hospital");
        }
        // Clear any other lead on the same shift first.
        clearShiftLeadForShift(
                target.getHospital().getId(), target.getShiftDate(), target.getShiftPeriod());

        target.setShiftLead(true);
        target = shiftAssignmentRepository.save(target);
        log.info("Shift-lead badge transferred to {} on {} {}",
                target.getUser().getEmail(), target.getShiftDate(), target.getShiftPeriod());
        return ShiftAssignmentMapper.toResponse(target);
    }
}
