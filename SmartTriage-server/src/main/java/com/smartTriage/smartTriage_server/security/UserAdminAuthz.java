package com.smartTriage.smartTriage_server.security;

import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Authorization helper for user-management endpoints. Wired into Spring
 * Security via SpEL — e.g.:
 *
 * <pre>{@code
 * @PreAuthorize("@userAdminAuthz.canEditUser(authentication, #id)")
 * }</pre>
 *
 * <h2>Policies</h2>
 *
 * The system has three user-creating actors:
 * <ol>
 *   <li><b>SUPER_ADMIN</b> — system-level. Can act across hospitals.</li>
 *   <li><b>HOSPITAL_ADMIN</b> — hospital-level. Bounded to own hospital.</li>
 *   <li>Everyone else — cannot create or modify user accounts.</li>
 * </ol>
 *
 * Specific rules enforced here:
 *
 * <ul>
 *   <li><b>Create.</b>
 *     <ul>
 *       <li>SUPER_ADMIN may create any role <em>except</em> another
 *           SUPER_ADMIN. (System bootstrap of the first SA happens
 *           outside the API; subsequent SAs are intentionally a manual
 *           DB-level decision to prevent privilege escalation.)</li>
 *       <li>HOSPITAL_ADMIN may create users at their own hospital
 *           <em>only</em>, and may not create HOSPITAL_ADMIN or
 *           SUPER_ADMIN. This stops a HA from minting peers or
 *           promoting themselves.</li>
 *     </ul></li>
 *
 *   <li><b>Edit personal info</b> (firstName, lastName, email,
 *       phoneNumber, professionalLicense). Identity-attribute editing
 *       requires account-level authority:
 *     <ul>
 *       <li>The user themselves — always (via Profile, separate
 *           endpoint; not gated here).</li>
 *       <li>HOSPITAL_ADMIN — for users at their own hospital
 *           <em>except</em> other HOSPITAL_ADMINs and SUPER_ADMINs
 *           (no peer/upward edits).</li>
 *       <li>SUPER_ADMIN — <em>cannot</em> edit personal info of any
 *           HOSPITAL_ADMIN or SUPER_ADMIN. SA's authority is
 *           system governance (suspend, role assignment, hospital
 *           transfer) not the personal data of named individuals.
 *           Letting SA quietly change a HA's email would compromise
 *           password-reset flows. Compromised SA → silently
 *           rerouted alerts.</li>
 *     </ul></li>
 *
 *   <li><b>Change role / designation / hospital / status.</b>
 *       Governance-grade edits — different rules:
 *     <ul>
 *       <li>SUPER_ADMIN — may change any user's role, designation,
 *           hospital, or accountStatus, except cannot promote anyone
 *           to SUPER_ADMIN.</li>
 *       <li>HOSPITAL_ADMIN — may change designation and status of
 *           users at their own hospital, except other admins.
 *           Cannot change role or hospital (those are SA-only
 *           transitions).</li>
 *     </ul></li>
 *
 *   <li><b>Deactivate / cancel invite.</b> Same authority as
 *       editing role/status above.</li>
 * </ul>
 */
@Slf4j
@Component("userAdminAuthz")
@RequiredArgsConstructor
public class UserAdminAuthz {

    private final UserRepository userRepository;

    /**
     * @return true when the caller may create a user with the given
     *         (role, hospitalId). The endpoint must pass both — the
     *         predicate considers them together.
     */
    @Transactional(readOnly = true)
    public boolean canCreateUserWithRole(Authentication authentication, Role newRole, UUID newHospitalId) {
        try {
            User caller = currentUser(authentication);
            if (caller == null || newRole == null) return false;

            if (newRole == Role.SUPER_ADMIN) {
                // No path to mint another SUPER_ADMIN through the API.
                // Forces SA promotion to be a deliberate, audited DB-level
                // operation rather than one weak token away.
                return false;
            }

            if (caller.getRole() == Role.SUPER_ADMIN) {
                // SA can create any non-SA role at any hospital.
                return true;
            }

            if (caller.getRole() == Role.HOSPITAL_ADMIN) {
                // HA cannot create another HA (no peer-creation).
                if (newRole == Role.HOSPITAL_ADMIN) return false;
                // HA bounded to own hospital.
                if (newHospitalId == null) return false;
                UUID callerHospitalId = userRepository
                        .findHospitalIdByUserId(caller.getId()).orElse(null);
                return newHospitalId.equals(callerHospitalId);
            }

            return false;
        } catch (Exception e) {
            log.error("canCreateUserWithRole error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * @return true when the caller may edit personal-info fields
     *         (firstName, lastName, email, phoneNumber,
     *         professionalLicense) on the target user.
     *
     *         <p>This is the strict gate. Governance-grade edits
     *         (role / designation / hospital / status) use
     *         {@link #canManageUser} which is a wider gate.
     */
    @Transactional(readOnly = true)
    public boolean canEditUserPersonalInfo(Authentication authentication, UUID targetUserId) {
        try {
            User caller = currentUser(authentication);
            if (caller == null || targetUserId == null) return false;

            // Self-edit is always allowed (route via the dedicated
            // Profile endpoint in practice; permitted here for
            // completeness).
            if (targetUserId.equals(caller.getId())) return true;

            User target = userRepository.findById(targetUserId).orElse(null);
            if (target == null) return false;

            // SUPER_ADMIN cannot edit personal info of any HA or
            // another SA. Personal info edits should be self-service.
            if (target.getRole() == Role.SUPER_ADMIN
                    || target.getRole() == Role.HOSPITAL_ADMIN) {
                return false;
            }

            // SUPER_ADMIN editing a regular user (DOCTOR, NURSE,
            // REGISTRAR, etc.) is allowed but rare in normal
            // operation; usually a HA at the user's hospital is the
            // right actor. Permitted for break-glass scenarios.
            if (caller.getRole() == Role.SUPER_ADMIN) return true;

            // HOSPITAL_ADMIN: own hospital, non-admin targets only.
            if (caller.getRole() == Role.HOSPITAL_ADMIN) {
                UUID callerHospitalId = userRepository
                        .findHospitalIdByUserId(caller.getId()).orElse(null);
                UUID targetHospitalId = userRepository
                        .findHospitalIdByUserId(target.getId()).orElse(null);
                return callerHospitalId != null
                        && callerHospitalId.equals(targetHospitalId);
            }

            return false;
        } catch (Exception e) {
            log.error("canEditUserPersonalInfo error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Wider gate for governance-grade edits: change role, designation,
     * hospital, deactivate, cancel invite, resend invite.
     */
    @Transactional(readOnly = true)
    public boolean canManageUser(Authentication authentication, UUID targetUserId) {
        try {
            User caller = currentUser(authentication);
            if (caller == null || targetUserId == null) return false;

            // Cannot manage yourself via the admin endpoint — prevents
            // the obvious foot-gun of an admin deactivating their own
            // account.
            if (targetUserId.equals(caller.getId())) return false;

            User target = userRepository.findById(targetUserId).orElse(null);
            if (target == null) return false;

            // No one can manage a SUPER_ADMIN through the API.
            if (target.getRole() == Role.SUPER_ADMIN) return false;

            if (caller.getRole() == Role.SUPER_ADMIN) {
                // SA can manage any non-SA user.
                return true;
            }

            if (caller.getRole() == Role.HOSPITAL_ADMIN) {
                // HA cannot manage another HA.
                if (target.getRole() == Role.HOSPITAL_ADMIN) return false;
                UUID callerHospitalId = userRepository
                        .findHospitalIdByUserId(caller.getId()).orElse(null);
                UUID targetHospitalId = userRepository
                        .findHospitalIdByUserId(target.getId()).orElse(null);
                return callerHospitalId != null
                        && callerHospitalId.equals(targetHospitalId);
            }

            return false;
        } catch (Exception e) {
            log.error("canManageUser error: {}", e.getMessage(), e);
            return false;
        }
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();
        return (principal instanceof User user) ? user : null;
    }

    /**
     * SpEL helper used by lab verification endpoints — returns true
     * when the caller's designation matches the supplied label.
     * Example: {@code @userAdminAuthz.hasDesignation(authentication, 'HEAD_LAB_TECHNICIAN')}.
     */
    @Transactional(readOnly = true)
    public boolean hasDesignation(Authentication authentication, String designation) {
        User caller = currentUser(authentication);
        if (caller == null || caller.getDesignation() == null || designation == null) return false;
        return caller.getDesignation().name().equalsIgnoreCase(designation);
    }
}
