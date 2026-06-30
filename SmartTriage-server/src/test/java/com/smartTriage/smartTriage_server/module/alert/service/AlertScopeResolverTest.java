package com.smartTriage.smartTriage_server.module.alert.service;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftAssignmentResponse;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.security.ClinicalAuthz;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The alert visibility matrix — verifies each role resolves to the correct scope.
 * This is the data-level enforcement the Alert Center query relies on.
 */
class AlertScopeResolverTest {

    private final ClinicalAuthz authz = mock(ClinicalAuthz.class);
    private final ShiftAssignmentService shifts = mock(ShiftAssignmentService.class);
    private final AlertScopeResolver resolver = new AlertScopeResolver(authz, shifts);

    private final UUID hospitalId = UUID.randomUUID();

    private Authentication authFor(User u) {
        return new UsernamePasswordAuthenticationToken(u, null);
    }

    private User user(Role role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        return u;
    }

    private void onShift(UUID userId, EdZone zone) {
        ShiftAssignmentResponse sa = mock(ShiftAssignmentResponse.class);
        when(sa.getHospitalId()).thenReturn(hospitalId);
        when(sa.getZone()).thenReturn(zone);
        when(sa.getAdditionalZones()).thenReturn(null);
        when(shifts.getCurrentShiftForUser(userId)).thenReturn(Optional.of(sa));
    }

    @Test
    void crossZoneRole_seesAll() {
        // Charge Nurse / shift lead / super-admin all surface via canSeeAllZonesAtHospital.
        User u = user(Role.NURSE);
        when(authz.canSeeAllZonesAtHospital(any(), any())).thenReturn(true);
        assertEquals(AlertScopeResolver.Kind.ALL, resolver.resolve(authFor(u), hospitalId).kind());
    }

    @Test
    void zoneNurse_seesOnlyTheirZone() {
        User u = user(Role.NURSE);
        when(authz.canSeeAllZonesAtHospital(any(), any())).thenReturn(false);
        when(authz.canAccessHospital(any(), any())).thenReturn(true);
        onShift(u.getId(), EdZone.GENERAL);

        AlertScopeResolver.AlertScope s = resolver.resolve(authFor(u), hospitalId);
        assertEquals(AlertScopeResolver.Kind.ZONE, s.kind());
        assertTrue(s.zones().contains(EdZone.GENERAL));
        assertEquals(u.getId(), s.userId());
    }

    @Test
    void doctor_isZoneScopedLikeNurse() {
        User u = user(Role.DOCTOR);
        when(authz.canSeeAllZonesAtHospital(any(), any())).thenReturn(false);
        when(authz.canAccessHospital(any(), any())).thenReturn(true);
        onShift(u.getId(), EdZone.ACUTE);

        AlertScopeResolver.AlertScope s = resolver.resolve(authFor(u), hospitalId);
        assertEquals(AlertScopeResolver.Kind.ZONE, s.kind());
        assertTrue(s.zones().contains(EdZone.ACUTE));
    }

    @Test
    void labTech_seesOnlyLabCategory() {
        User u = user(Role.LAB_TECHNICIAN);
        when(authz.canSeeAllZonesAtHospital(any(), any())).thenReturn(false);
        when(authz.canAccessHospital(any(), any())).thenReturn(true);

        AlertScopeResolver.AlertScope s = resolver.resolve(authFor(u), hospitalId);
        assertEquals(AlertScopeResolver.Kind.CATEGORY, s.kind());
        assertTrue(s.alertTypes().contains(AlertType.CRITICAL_LAB_RESULT));
        assertTrue(s.alertTypes().contains(AlertType.STAT_LAB_OVERDUE));
    }

    @Test
    void paramedicAndRegistrar_seeNothingInClinicalCenter() {
        when(authz.canSeeAllZonesAtHospital(any(), any())).thenReturn(false);
        when(authz.canAccessHospital(any(), any())).thenReturn(true);
        assertEquals(AlertScopeResolver.Kind.NONE,
                resolver.resolve(authFor(user(Role.PARAMEDIC)), hospitalId).kind());
        assertEquals(AlertScopeResolver.Kind.NONE,
                resolver.resolve(authFor(user(Role.REGISTRAR)), hospitalId).kind());
    }

    @Test
    void hospitalAdmin_deniedBeforeAnyAllZonesGrant() {
        // Guard runs first — even if canSeeAllZonesAtHospital would grant ALL.
        User u = user(Role.HOSPITAL_ADMIN);
        assertEquals(AlertScopeResolver.Kind.NONE, resolver.resolve(authFor(u), hospitalId).kind());
    }

    @Test
    void zoneNurseWithNoActiveShift_fallsBackToPersonalScope() {
        User u = user(Role.NURSE);
        when(authz.canSeeAllZonesAtHospital(any(), any())).thenReturn(false);
        when(authz.canAccessHospital(any(), any())).thenReturn(true);
        when(shifts.getCurrentShiftForUser(u.getId())).thenReturn(Optional.empty());

        AlertScopeResolver.AlertScope s = resolver.resolve(authFor(u), hospitalId);
        assertEquals(AlertScopeResolver.Kind.ZONE, s.kind());
        assertTrue(s.zones().isEmpty()); // → service uses findPersonalScopedAlerts
        assertEquals(u.getId(), s.userId());
    }
}
