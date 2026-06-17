package com.smartTriage.smartTriage_server.security;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.DiagnosisRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.handover.repository.HandoverReportRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Override-Audit authorization added to fix the blank
 * Override Alerts page: {@code canAuditSafetyOverrides} (who may READ the
 * forensic dashboard) and {@code canAcknowledgeSafetyOverride} (who may sign
 * off an override, and only on override rows — never operational alerts).
 */
class ClinicalAuthzTest {

    private UserRepository userRepository;
    private ShiftAssignmentService shiftAssignmentService;
    private ClinicalAlertRepository clinicalAlertRepository;
    private com.smartTriage.smartTriage_server.module.sepsis.repository.SepsisScreeningRepository sepsisScreeningRepository;
    private com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository fastTrackActivationRepository;
    private com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository hypoglycemiaEventRepository;
    private com.smartTriage.smartTriage_server.module.isolation.repository.InfectionScreeningRepository infectionScreeningRepository;
    private ClinicalAuthz authz;

    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        shiftAssignmentService = mock(ShiftAssignmentService.class);
        clinicalAlertRepository = mock(ClinicalAlertRepository.class);
        sepsisScreeningRepository =
                mock(com.smartTriage.smartTriage_server.module.sepsis.repository.SepsisScreeningRepository.class);
        fastTrackActivationRepository =
                mock(com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository.class);
        hypoglycemiaEventRepository =
                mock(com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository.class);
        infectionScreeningRepository =
                mock(com.smartTriage.smartTriage_server.module.isolation.repository.InfectionScreeningRepository.class);
        authz = new ClinicalAuthz(
                userRepository,
                mock(VisitRepository.class),
                mock(PatientRepository.class),
                shiftAssignmentService,
                mock(ClinicalNoteRepository.class),
                mock(DiagnosisRepository.class),
                mock(InvestigationRepository.class),
                mock(HandoverReportRepository.class),
                clinicalAlertRepository,
                sepsisScreeningRepository,
                fastTrackActivationRepository,
                hypoglycemiaEventRepository,
                infectionScreeningRepository);
    }

    @Test
    void canAccessInfectionScreening_deniesUnknownScreening() {
        UUID missing = UUID.randomUUID();
        when(infectionScreeningRepository.findVisitIdById(missing)).thenReturn(java.util.Optional.empty());
        assertFalse(authz.canAccessInfectionScreening(
                authFor(user(Role.DOCTOR, null, hospitalId)), missing));
    }

    @Test
    void canAccessFastTrack_deniesUnknownActivation() {
        UUID missing = UUID.randomUUID();
        when(fastTrackActivationRepository.findVisitIdById(missing)).thenReturn(java.util.Optional.empty());
        assertFalse(authz.canAccessFastTrack(
                authFor(user(Role.DOCTOR, null, hospitalId)), missing));
    }

    @Test
    void canAccessHypoglycemiaEvent_deniesUnknownEvent() {
        UUID missing = UUID.randomUUID();
        when(hypoglycemiaEventRepository.findVisitIdById(missing)).thenReturn(java.util.Optional.empty());
        assertFalse(authz.canAccessHypoglycemiaEvent(
                authFor(user(Role.DOCTOR, null, hospitalId)), missing));
    }

    @Test
    void canAccessSepsisScreening_deniesUnknownScreening() {
        UUID missing = UUID.randomUUID();
        when(sepsisScreeningRepository.findVisitIdById(missing)).thenReturn(java.util.Optional.empty());
        assertFalse(authz.canAccessSepsisScreening(
                authFor(user(Role.DOCTOR, null, hospitalId)), missing));
    }

    private Authentication authFor(User user) {
        return new UsernamePasswordAuthenticationToken(user, null);
    }

    private User user(Role role, Designation designation, UUID atHospital) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        u.setDesignation(designation);
        lenient().when(userRepository.findHospitalIdByUserId(u.getId()))
                .thenReturn(Optional.ofNullable(atHospital));
        return u;
    }

    // ── canAuditSafetyOverrides ──────────────────────────────────────

    @Test
    void governanceAndSeniorClinicalRolesCanAuditAtTheirHospital() {
        assertTrue(authz.canAuditSafetyOverrides(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), hospitalId));
        assertTrue(authz.canAuditSafetyOverrides(authFor(user(Role.READ_ONLY, null, hospitalId)), hospitalId));
        assertTrue(authz.canAuditSafetyOverrides(authFor(user(Role.DOCTOR, null, hospitalId)), hospitalId));
        assertTrue(authz.canAuditSafetyOverrides(
                authFor(user(Role.NURSE, Designation.CHARGE_NURSE, hospitalId)), hospitalId));
    }

    @Test
    void superAdminCanAuditAnyHospital() {
        assertTrue(authz.canAuditSafetyOverrides(authFor(user(Role.SUPER_ADMIN, null, null)), hospitalId));
    }

    @Test
    void shiftLeadNurseCanAuditEvenWithoutChargeDesignation() {
        User nurse = user(Role.NURSE, Designation.STAFF_NURSE, hospitalId);
        when(shiftAssignmentService.isUserCurrentShiftLead(nurse.getId(), hospitalId)).thenReturn(true);
        assertTrue(authz.canAuditSafetyOverrides(authFor(nurse), hospitalId));
    }

    @Test
    void plainNurseWithoutLeadCannotAudit() {
        User nurse = user(Role.NURSE, Designation.STAFF_NURSE, hospitalId);
        when(shiftAssignmentService.isUserCurrentShiftLead(nurse.getId(), hospitalId)).thenReturn(false);
        assertFalse(authz.canAuditSafetyOverrides(authFor(nurse), hospitalId));
    }

    @Test
    void registrarAndParamedicCannotAudit() {
        assertFalse(authz.canAuditSafetyOverrides(authFor(user(Role.REGISTRAR, null, hospitalId)), hospitalId));
        assertFalse(authz.canAuditSafetyOverrides(authFor(user(Role.PARAMEDIC, null, hospitalId)), hospitalId));
    }

    @Test
    void cannotAuditADifferentHospital() {
        // Doctor belongs to a different hospital than the one requested.
        User doctor = user(Role.DOCTOR, null, UUID.randomUUID());
        assertFalse(authz.canAuditSafetyOverrides(authFor(doctor), hospitalId));
    }

    @Test
    void nullAuthOrPrincipalDenied() {
        assertFalse(authz.canAuditSafetyOverrides(null, hospitalId));
        assertFalse(authz.canAuditSafetyOverrides(
                new UsernamePasswordAuthenticationToken("not-a-user", null), hospitalId));
    }

    // ── canAcknowledgeSafetyOverride ─────────────────────────────────

    private ClinicalAlert overrideAlert(AlertType type) {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        Visit v = new Visit();
        v.setId(UUID.randomUUID());
        v.setHospital(h);
        ClinicalAlert a = ClinicalAlert.builder().visit(v).alertType(type).build();
        a.setId(UUID.randomUUID());
        return a;
    }

    @Test
    void canAcknowledgeMedicationSafetyWarningOverride() {
        ClinicalAlert a = overrideAlert(AlertType.MEDICATION_SAFETY_WARNING);
        when(clinicalAlertRepository.findByIdAndIsActiveTrue(a.getId())).thenReturn(Optional.of(a));
        assertTrue(authz.canAcknowledgeSafetyOverride(
                authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), a.getId()));
    }

    @Test
    void canAcknowledgeEmergencyOverride() {
        ClinicalAlert a = overrideAlert(AlertType.MEDICATION_EMERGENCY_OVERRIDE);
        when(clinicalAlertRepository.findByIdAndIsActiveTrue(a.getId())).thenReturn(Optional.of(a));
        assertTrue(authz.canAcknowledgeSafetyOverride(
                authFor(user(Role.READ_ONLY, null, hospitalId)), a.getId()));
    }

    @Test
    void cannotUseOverrideAckPathOnOperationalAlert() {
        // A TEWS_CRITICAL operational alert must NOT be acknowledgeable via the
        // override path, even by an otherwise-authorized auditor.
        ClinicalAlert a = overrideAlert(AlertType.TEWS_CRITICAL);
        when(clinicalAlertRepository.findByIdAndIsActiveTrue(a.getId())).thenReturn(Optional.of(a));
        assertFalse(authz.canAcknowledgeSafetyOverride(
                authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), a.getId()));
    }

    @Test
    void missingAlertDenied() {
        UUID missing = UUID.randomUUID();
        when(clinicalAlertRepository.findByIdAndIsActiveTrue(missing)).thenReturn(Optional.empty());
        assertFalse(authz.canAcknowledgeSafetyOverride(
                authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), missing));
    }
}
