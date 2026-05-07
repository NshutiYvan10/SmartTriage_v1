package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftSwapRequest;
import com.smartTriage.smartTriage_server.module.shift.entity.StaffLeave;
import com.smartTriage.smartTriage_server.module.shift.repository.ChargeNurseDelegationRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftAssignmentRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftSwapRequestRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.StaffLeaveRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Authorization helper for shift-assignment endpoints. Wired into Spring
 * Security via SpEL — e.g.:
 *
 * <pre>{@code
 * @PreAuthorize("@shiftAssignmentAuthz.canAssign(authentication, #hospitalId)")
 * }</pre>
 *
 * <p>The allowed actors for a given hospital are:
 *
 * <ol>
 *   <li>{@code SUPER_ADMIN} — always.</li>
 *   <li>{@code HOSPITAL_ADMIN} for that hospital — always (fallback).</li>
 *   <li><b>Charge nurses</b> for that hospital ({@code Designation.CHARGE_NURSE})
 *       — unit-management is part of their role in Rwandan EDs (CHUK, KFH, RMH),
 *       so zone/shift assignment is a normal daily duty for them, not something
 *       that requires a special badge.</li>
 *   <li>The user currently holding the shift-lead badge for that hospital.</li>
 *   <li>The user who held the badge in the previous shift, within the
 *       {@link ShiftAssignmentService#SHIFT_LEAD_GRACE grace window}, so
 *       the changeover never has an authority gap.</li>
 *   <li>An <b>acting Charge Nurse</b> with a currently-valid
 *       {@link com.smartTriage.smartTriage_server.module.shift.entity.ChargeNurseDelegation}
 *       row pointing at them — used when the on-duty CN has formally
 *       delegated authority for a defined window (off-site meeting,
 *       short absence) without leaving the unit without a single point
 *       of authority.</li>
 * </ol>
 *
 * <h2>Exception-safety</h2>
 * This class is invoked by Spring Security's SpEL evaluator, which runs
 * <em>before</em> the controller method opens its transaction. If an exception
 * escapes here — for example a {@code LazyInitializationException} on the
 * {@code User#hospital} association (the JWT filter loads the user inside a
 * short-lived {@code @Transactional(readOnly=true)} session that closes before
 * SpEL runs) — Spring maps it to a 500, which is what the user sees as
 * <em>"An unexpected error occurred. Contact system administrator."</em>
 *
 * <p>Every public method therefore has a defensive try/catch that returns
 * {@code false} on any unexpected error. Permission checks MUST fail closed
 * (deny, producing a 403) rather than leak a 500 — both for security and for
 * UX: a frontline charge nurse should never see an "unexpected error" when
 * what they're hitting is really a permission boundary.
 *
 * <p>Hospital membership is resolved via a primitive-projection JPQL query
 * ({@link UserRepository#findHospitalIdByUserId}) so we never dereference the
 * lazy {@code User#hospital} reference from a detached principal.
 */
@Slf4j
@Component("shiftAssignmentAuthz")
@RequiredArgsConstructor
public class ShiftAssignmentAuthz {

    private final ShiftAssignmentService shiftAssignmentService;
    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final UserRepository userRepository;
    private final ChargeNurseDelegationRepository chargeNurseDelegationRepository;
    private final StaffLeaveRepository staffLeaveRepository;
    private final ShiftSwapRequestRepository shiftSwapRequestRepository;

    /**
     * @return true if the authenticated user may assign staff for the given
     *         hospital right now.
     */
    @Transactional(readOnly = true)
    public boolean canAssign(Authentication authentication, UUID hospitalId) {
        try {
            User user = currentUser(authentication);
            if (user == null || hospitalId == null) {
                return false;
            }

            // Super admin can always act.
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }

            boolean sameHospital = belongsToHospital(user, hospitalId);

            // Hospital admin for the target hospital — fallback authority.
            // Admins remain accountable even on leave (governance reach is
            // their job); only clinicians are gated by the leave check below.
            if (user.getRole() == Role.HOSPITAL_ADMIN && sameHospital) {
                return true;
            }

            // V44+ off-duty guard: a clinician (DOCTOR / NURSE / etc.) on
            // approved leave covering today is not on the floor. Their
            // role-based authority — including CHARGE_NURSE designation,
            // shift-lead badge, and any active delegation — is suspended
            // until the leave window ends. Without this check, a CN whose
            // own leave is APPROVED could still rubber-stamp swap/leave
            // approvals from home, defeating the approval path's intent.
            //
            // Admins (SUPER_ADMIN / HOSPITAL_ADMIN) above this point are
            // already returned; they're not affected by this guard.
            if (isOnApprovedLeaveToday(user.getId())) {
                log.debug("canAssign denied for {} — user is on approved leave today",
                        user.getEmail());
                return false;
            }

            // Charge nurses manage the unit in Rwandan EDs — they own zone
            // and shift assignment as part of day-to-day operations.
            //
            // Defence-in-depth: also require role = NURSE. The user-create
            // path validates role/designation pairs, but checking role here
            // means a stale or otherwise corrupted DOCTOR-with-CHARGE_NURSE
            // record can never grant nurse-management authority.
            if (user.getRole() == Role.NURSE
                    && user.getDesignation() == Designation.CHARGE_NURSE
                    && sameHospital) {
                return true;
            }

            // The current shift-lead badge holder.
            if (shiftAssignmentService.isUserCurrentShiftLead(user.getId(), hospitalId)) {
                return true;
            }

            // Previous shift-lead, still inside the grace window.
            if (shiftAssignmentService.isUserWithinShiftLeadGrace(user.getId(), hospitalId)) {
                return true;
            }

            // Acting Charge Nurse — a CN has formally delegated authority
            // to this user for a defined window (see ChargeNurseDelegation
            // and V41). Active rows here grant canAssign authority just
            // like the CN designation would; only NURSE-role users with
            // sameHospital are eligible (defence-in-depth: prevents a
            // stale row pointing at a non-nurse from leaking authority).
            if (sameHospital
                    && user.getRole() == Role.NURSE
                    && chargeNurseDelegationRepository
                            .findActiveDelegationForDelegate(hospitalId, user.getId(), Instant.now())
                            .isPresent()) {
                return true;
            }

            log.debug("canAssign denied for {} on hospital {}", user.getEmail(), hospitalId);
            return false;

        } catch (Exception e) {
            // Fail closed — never let an internal error become a 500 on the
            // authorization chain. A denied request yields a clean 403, which
            // the global exception handler can surface to the UI as a
            // permission error instead of "contact system administrator".
            log.error("canAssign evaluation error for hospital {}: {}", hospitalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Overload for endpoints that only have the {@code assignmentId} in the
     * URL — we look up the underlying {@link ShiftAssignment} and delegate to
     * {@link #canAssign(Authentication, UUID)} with its hospital.
     */
    @Transactional(readOnly = true)
    public boolean canAssignForAssignment(Authentication authentication, UUID assignmentId) {
        try {
            if (assignmentId == null) {
                return false;
            }
            return shiftAssignmentRepository.findById(assignmentId)
                    .map(sa -> sa.getHospital() != null
                            ? canAssign(authentication, sa.getHospital().getId())
                            : false)
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAssignForAssignment evaluation error for assignment {}: {}",
                    assignmentId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Resolve hospital from a leave row, then delegate to {@link #canAssign}.
     * Used by {@code @PreAuthorize} on leave-decision endpoints — the
     * caller doesn't pass {@code hospitalId} explicitly because it lives
     * on the leave row itself.
     */
    @Transactional(readOnly = true)
    public boolean canAssignForLeave(Authentication authentication, UUID leaveId) {
        try {
            if (leaveId == null) return false;
            return staffLeaveRepository.findById(leaveId)
                    .map(StaffLeave::getHospital)
                    .map(h -> canAssign(authentication, h.getId()))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAssignForLeave evaluation error for leave {}: {}",
                    leaveId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Resolve hospital from a swap request, then delegate to {@link #canAssign}.
     */
    @Transactional(readOnly = true)
    public boolean canAssignForSwap(Authentication authentication, UUID swapId) {
        try {
            if (swapId == null) return false;
            return shiftSwapRequestRepository.findById(swapId)
                    .map(ShiftSwapRequest::getHospital)
                    .map(h -> canAssign(authentication, h.getId()))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAssignForSwap evaluation error for swap {}: {}",
                    swapId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Authority to create / edit / delete templates. Templates describe the
     * hospital's default roster, so they are an admin-plane concern — only
     * SUPER_ADMIN and the HOSPITAL_ADMIN for that hospital may mutate them.
     * The shift-lead can always apply them (via canAssign) but cannot
     * rewrite them.
     */
    @Transactional(readOnly = true)
    public boolean canManageTemplates(Authentication authentication, UUID hospitalId) {
        try {
            User user = currentUser(authentication);
            if (user == null || hospitalId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            return user.getRole() == Role.HOSPITAL_ADMIN && belongsToHospital(user, hospitalId);
        } catch (Exception e) {
            log.error("canManageTemplates evaluation error for hospital {}: {}",
                    hospitalId, e.getMessage(), e);
            return false;
        }
    }

    /* ─────────────────────────── helpers ─────────────────────────── */

    private User currentUser(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return (principal instanceof User user) ? user : null;
    }

    /**
     * Resolve the user's hospital id <em>without</em> dereferencing the lazy
     * {@code User#hospital} association. The principal is a detached entity
     * (loaded by the JWT filter in a session that has since closed), so
     * reading {@code user.getHospital()} directly can throw
     * {@code LazyInitializationException}. Instead we issue a tiny projection
     * query that returns just the hospital id.
     */
    private boolean belongsToHospital(User user, UUID hospitalId) {
        if (user == null || user.getId() == null || hospitalId == null) {
            return false;
        }
        Optional<UUID> resolved = userRepository.findHospitalIdByUserId(user.getId());
        return resolved.map(hospitalId::equals).orElse(false);
    }

    /**
     * V44+ off-duty guard. True when the user has at least one approved
     * StaffLeave row whose [startsOn, endsOn] window covers today
     * (Africa/Kigali — the operational time zone of the deployment).
     *
     * <p>Used by {@link #canAssign} to deny shift-management actions
     * (assignments, approvals, delegation creation) for clinicians who
     * are formally off the floor. Admins are not gated by this check;
     * they bypass to true earlier in {@code canAssign}.
     */
    private boolean isOnApprovedLeaveToday(UUID userId) {
        if (userId == null) return false;
        // Africa/Kigali is the system's operational timezone (Rwanda).
        // We compute "today" in that zone so a leave row that ends today
        // still blocks today, regardless of where the JVM happens to think
        // the date boundary sits.
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Kigali"));
        return !staffLeaveRepository.findApprovedCovering(userId, today).isEmpty();
    }
}
