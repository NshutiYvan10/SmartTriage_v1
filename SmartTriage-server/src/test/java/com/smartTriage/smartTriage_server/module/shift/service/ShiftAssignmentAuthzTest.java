package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.shift.entity.ChargeNurseDelegation;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplate;
import com.smartTriage.smartTriage_server.module.shift.repository.ChargeNurseDelegationRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftAssignmentRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftSwapRequestRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftTemplateRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.StaffLeaveRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the authz follow-up methods added to {@link ShiftAssignmentAuthz}:
 * the template-aware mutate guard and the delegation-revoke guard. Both close
 * cross-hospital holes, so the tests assert a same-hospital actor is allowed and
 * a foreign-hospital actor is denied. Real entities (ids set via setter) + mocked
 * repositories — entity getId() is a Lombok final-free getter but mocking the
 * entities themselves is brittle, so we use real instances.
 */
class ShiftAssignmentAuthzTest {

    private ShiftAssignmentService shiftAssignmentService;
    private UserRepository userRepository;
    private ChargeNurseDelegationRepository delegationRepository;
    private StaffLeaveRepository staffLeaveRepository;
    private ShiftTemplateRepository shiftTemplateRepository;
    private ShiftAssignmentAuthz authz;

    private final UUID HOSPITAL_A = UUID.randomUUID();
    private final UUID HOSPITAL_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        shiftAssignmentService = mock(ShiftAssignmentService.class);
        ShiftAssignmentRepository shiftAssignmentRepository = mock(ShiftAssignmentRepository.class);
        userRepository = mock(UserRepository.class);
        delegationRepository = mock(ChargeNurseDelegationRepository.class);
        staffLeaveRepository = mock(StaffLeaveRepository.class);
        ShiftSwapRequestRepository shiftSwapRequestRepository = mock(ShiftSwapRequestRepository.class);
        shiftTemplateRepository = mock(ShiftTemplateRepository.class);

        authz = new ShiftAssignmentAuthz(
                shiftAssignmentService, shiftAssignmentRepository, userRepository,
                delegationRepository, staffLeaveRepository, shiftSwapRequestRepository,
                shiftTemplateRepository);

        lenient().when(shiftAssignmentService.isUserCurrentShiftLead(any(), any())).thenReturn(false);
        lenient().when(shiftAssignmentService.isUserWithinShiftLeadGrace(any(), any())).thenReturn(false);
        lenient().when(staffLeaveRepository.findApprovedCovering(any(), any(LocalDate.class)))
                .thenReturn(List.of());
    }

    private User user(Role role, Designation designation) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        u.setDesignation(designation);
        u.setEmail("u-" + u.getId() + "@it.test");
        return u;
    }

    private Authentication auth(User u) {
        return new UsernamePasswordAuthenticationToken(u, null, List.of());
    }

    private ChargeNurseDelegation delegation(UUID hospitalId, User delegatingUser, User delegate) {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        ChargeNurseDelegation d = new ChargeNurseDelegation();
        d.setHospital(h);
        d.setDelegatingUser(delegatingUser);
        d.setDelegate(delegate);
        return d;
    }

    private ShiftTemplate template(UUID hospitalId) {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        ShiftTemplate t = new ShiftTemplate();
        t.setHospital(h);
        return t;
    }

    // ── canRevokeDelegation ─────────────────────────────────────────

    @Test
    void revoke_delegatingCn_allowed() {
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        User delegate = user(Role.NURSE, Designation.STAFF_NURSE);
        UUID id = UUID.randomUUID();
        when(delegationRepository.findById(id)).thenReturn(Optional.of(delegation(HOSPITAL_A, cn, delegate)));
        assertTrue(authz.canRevokeDelegation(auth(cn), id));
    }

    @Test
    void revoke_namedDelegate_allowed() {
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        User delegate = user(Role.NURSE, Designation.STAFF_NURSE);
        UUID id = UUID.randomUUID();
        when(delegationRepository.findById(id)).thenReturn(Optional.of(delegation(HOSPITAL_A, cn, delegate)));
        assertTrue(authz.canRevokeDelegation(auth(delegate), id));
    }

    @Test
    void revoke_superAdmin_allowed() {
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        User delegate = user(Role.NURSE, Designation.STAFF_NURSE);
        User sa = user(Role.SUPER_ADMIN, null);
        UUID id = UUID.randomUUID();
        when(delegationRepository.findById(id)).thenReturn(Optional.of(delegation(HOSPITAL_A, cn, delegate)));
        assertTrue(authz.canRevokeDelegation(auth(sa), id));
    }

    @Test
    void revoke_sameHospitalAdmin_allowed() {
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        User delegate = user(Role.NURSE, Designation.STAFF_NURSE);
        User ha = user(Role.HOSPITAL_ADMIN, null);
        UUID id = UUID.randomUUID();
        when(delegationRepository.findById(id)).thenReturn(Optional.of(delegation(HOSPITAL_A, cn, delegate)));
        when(userRepository.findHospitalIdByUserId(ha.getId())).thenReturn(Optional.of(HOSPITAL_A));
        assertTrue(authz.canRevokeDelegation(auth(ha), id));
    }

    @Test
    void revoke_foreignHospitalAdmin_denied() {
        // The closed gap: a HOSPITAL_ADMIN of hospital B must NOT revoke hospital A's delegation.
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        User delegate = user(Role.NURSE, Designation.STAFF_NURSE);
        User foreignHa = user(Role.HOSPITAL_ADMIN, null);
        UUID id = UUID.randomUUID();
        when(delegationRepository.findById(id)).thenReturn(Optional.of(delegation(HOSPITAL_A, cn, delegate)));
        when(userRepository.findHospitalIdByUserId(foreignHa.getId())).thenReturn(Optional.of(HOSPITAL_B));
        assertFalse(authz.canRevokeDelegation(auth(foreignHa), id));
    }

    @Test
    void revoke_unrelatedNurse_denied() {
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        User delegate = user(Role.NURSE, Designation.STAFF_NURSE);
        User other = user(Role.NURSE, Designation.STAFF_NURSE);
        UUID id = UUID.randomUUID();
        when(delegationRepository.findById(id)).thenReturn(Optional.of(delegation(HOSPITAL_A, cn, delegate)));
        assertFalse(authz.canRevokeDelegation(auth(other), id));
    }

    @Test
    void revoke_notFound_denied() {
        User sa = user(Role.SUPER_ADMIN, null);
        UUID id = UUID.randomUUID();
        when(delegationRepository.findById(id)).thenReturn(Optional.empty());
        assertFalse(authz.canRevokeDelegation(auth(sa), id));
    }

    // ── canManageTemplateById ───────────────────────────────────────

    @Test
    void manageTemplate_nullId_denied() {
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        assertFalse(authz.canManageTemplateById(auth(cn), null));
    }

    @Test
    void manageTemplate_notFound_denied() {
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        UUID id = UUID.randomUUID();
        when(shiftTemplateRepository.findById(id)).thenReturn(Optional.empty());
        assertFalse(authz.canManageTemplateById(auth(cn), id));
    }

    @Test
    void manageTemplate_sameHospitalChargeNurse_allowed() {
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        UUID id = UUID.randomUUID();
        when(shiftTemplateRepository.findById(id)).thenReturn(Optional.of(template(HOSPITAL_A)));
        when(userRepository.findHospitalIdByUserId(cn.getId())).thenReturn(Optional.of(HOSPITAL_A));
        assertTrue(authz.canManageTemplateById(auth(cn), id));
    }

    @Test
    void manageTemplate_foreignHospitalChargeNurse_denied() {
        // The closed gap: a CN at hospital B cannot edit hospital A's template.
        User cn = user(Role.NURSE, Designation.CHARGE_NURSE);
        UUID id = UUID.randomUUID();
        when(shiftTemplateRepository.findById(id)).thenReturn(Optional.of(template(HOSPITAL_A)));
        when(userRepository.findHospitalIdByUserId(cn.getId())).thenReturn(Optional.of(HOSPITAL_B));
        assertFalse(authz.canManageTemplateById(auth(cn), id));
    }

    @Test
    void manageTemplate_plainNurse_denied() {
        // Tightening: a plain NURSE (no CHARGE_NURSE designation) at the same hospital is denied,
        // matching the canAssign/canManageTemplates policy the POST endpoint already enforces.
        User plain = user(Role.NURSE, Designation.STAFF_NURSE);
        UUID id = UUID.randomUUID();
        when(shiftTemplateRepository.findById(id)).thenReturn(Optional.of(template(HOSPITAL_A)));
        when(userRepository.findHospitalIdByUserId(plain.getId())).thenReturn(Optional.of(HOSPITAL_A));
        assertFalse(authz.canManageTemplateById(auth(plain), id));
    }
}
