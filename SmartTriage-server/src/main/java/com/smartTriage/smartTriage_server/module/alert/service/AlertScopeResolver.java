package com.smartTriage.smartTriage_server.module.alert.service;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.security.ClinicalAuthz;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for WHICH alerts a user may see in the Alert Center,
 * by role + designation + current zone assignment. Enforced in the query (data
 * level) — NOT as a client-side filter — and mirrored by the WebSocket SUBSCRIBE
 * authz so live pop-ups and the historical feed are scoped identically.
 *
 * <p>Visibility policy:
 * <ul>
 *   <li><b>Super-Admin, Charge Nurse, current shift lead, Read-Only (auditor)</b>
 *       → ALL zones (oversight).</li>
 *   <li><b>Zone Nurse + Doctor</b> → only their covered zone(s), PLUS alerts
 *       targeted to them personally (escalations) and alerts they acknowledged.
 *       So scoping narrows the start and escalation widens it — they complement.</li>
 *   <li><b>Lab Technician</b> → laboratory alerts only (critical results / lab SLA),
 *       hospital-wide; no triage/EMS/zone clinical noise.</li>
 *   <li><b>Paramedic, Registrar, Hospital-Admin</b> → NONE (the clinical Alert
 *       Center is not their surface; paramedics use the EMS board, and the
 *       endpoint already denies Hospital-Admin).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertScopeResolver {

    private final ClinicalAuthz clinicalAuthz;
    private final ShiftAssignmentService shiftAssignmentService;

    /** Lab-tech visibility: laboratory alerts only, hospital-wide. */
    private static final Set<AlertType> LAB_ALERT_TYPES = EnumSet.of(
            AlertType.CRITICAL_LAB_RESULT,
            AlertType.CRITICAL_VALUE_UNACKNOWLEDGED,
            AlertType.STAT_LAB_OVERDUE,
            AlertType.URGENT_LAB_OVERDUE,
            AlertType.ROUTINE_LAB_OVERDUE,
            AlertType.LAB_NOT_RECEIVED,
            AlertType.LAB_SPECIMEN_REJECTED,
            AlertType.LAB_VERIFICATION_OVERRIDDEN);

    public enum Kind { ALL, ZONE, CATEGORY, NONE }

    /**
     * @param kind       which delivery strategy applies
     * @param zones      covered zones (ZONE kind); may be empty → personal-only
     * @param userId     caller id (ZONE kind) for personally-targeted/acked alerts
     * @param alertTypes allowed types (CATEGORY kind)
     */
    public record AlertScope(Kind kind, Set<EdZone> zones, UUID userId, Set<AlertType> alertTypes) {
        static AlertScope all()  { return new AlertScope(Kind.ALL, Set.of(), null, Set.of()); }
        static AlertScope none() { return new AlertScope(Kind.NONE, Set.of(), null, Set.of()); }
        static AlertScope zone(Set<EdZone> zones, UUID userId) {
            return new AlertScope(Kind.ZONE, zones, userId, Set.of());
        }
        static AlertScope category(Set<AlertType> types) {
            return new AlertScope(Kind.CATEGORY, Set.of(), null, types);
        }
    }

    @Transactional(readOnly = true)
    public AlertScope resolve(Authentication auth, UUID hospitalId) {
        User user = (auth != null && auth.getPrincipal() instanceof User u) ? u : null;
        if (user == null || hospitalId == null) {
            return AlertScope.none();
        }
        Role role = user.getRole();

        // Hospital-Admin: clinical alerts are not an administrator surface (S6).
        // Guard first — canSeeAllZonesAtHospital would otherwise grant them ALL.
        if (role == Role.HOSPITAL_ADMIN) {
            return AlertScope.none();
        }

        // Oversight / cross-zone roles (Super-Admin, Charge Nurse, current shift
        // lead) see everything at their hospital.
        if (clinicalAuthz.canSeeAllZonesAtHospital(auth, hospitalId)) {
            return AlertScope.all();
        }

        // Everything below requires hospital membership.
        if (!clinicalAuthz.canAccessHospital(auth, hospitalId)) {
            return AlertScope.none();
        }

        switch (role) {
            case READ_ONLY:
                // Safety officer / auditor — read-only oversight across the floor.
                return AlertScope.all();
            case LAB_TECHNICIAN:
                return AlertScope.category(LAB_ALERT_TYPES);
            case DOCTOR:
            case NURSE:
                // Zone-scoped: covered zone(s) + personally-targeted + acked.
                return AlertScope.zone(currentCoveredZones(user.getId(), hospitalId), user.getId());
            default:
                // PARAMEDIC (EMS board is their surface), REGISTRAR (front desk).
                return AlertScope.none();
        }
    }

    /** The user's currently-covered zones = active shift's primary zone ∪ additional zones. */
    private Set<EdZone> currentCoveredZones(UUID userId, UUID hospitalId) {
        Set<EdZone> zones = new HashSet<>();
        try {
            shiftAssignmentService.getCurrentShiftForUser(userId).ifPresent(sa -> {
                if (sa.getHospitalId() == null || sa.getHospitalId().equals(hospitalId)) {
                    if (sa.getZone() != null) {
                        zones.add(sa.getZone());
                    }
                    if (sa.getAdditionalZones() != null) {
                        zones.addAll(sa.getAdditionalZones());
                    }
                }
            });
        } catch (Exception e) {
            log.warn("[alert-scope] Could not resolve covered zones for user {}: {}", userId, e.toString());
        }
        return zones;
    }
}
