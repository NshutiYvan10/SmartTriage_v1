package com.smartTriage.smartTriage_server.security;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.ReportLevel;
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
    private com.smartTriage.smartTriage_server.module.pathway.repository.PathwayActivationRepository pathwayActivationRepository;
    private com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository labOrderRepository;
    private VisitRepository visitRepository;
    private com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository clinicalDocumentRepository;
    private com.smartTriage.smartTriage_server.module.consent.repository.InformedConsentRepository informedConsentRepository;
    private com.smartTriage.smartTriage_server.module.referral.repository.ReferralRepository referralRepository;
    private com.smartTriage.smartTriage_server.module.reporting.repository.MohReportRepository mohReportRepository;
    private com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository medicationSafetyCheckRepository;
    private com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository ioTDeviceRepository;
    private HandoverReportRepository handoverReportRepository;
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
        pathwayActivationRepository =
                mock(com.smartTriage.smartTriage_server.module.pathway.repository.PathwayActivationRepository.class);
        labOrderRepository =
                mock(com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository.class);
        visitRepository = mock(VisitRepository.class);
        clinicalDocumentRepository =
                mock(com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository.class);
        informedConsentRepository =
                mock(com.smartTriage.smartTriage_server.module.consent.repository.InformedConsentRepository.class);
        referralRepository =
                mock(com.smartTriage.smartTriage_server.module.referral.repository.ReferralRepository.class);
        mohReportRepository =
                mock(com.smartTriage.smartTriage_server.module.reporting.repository.MohReportRepository.class);
        medicationSafetyCheckRepository =
                mock(com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository.class);
        ioTDeviceRepository =
                mock(com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository.class);
        handoverReportRepository = mock(HandoverReportRepository.class);
        authz = new ClinicalAuthz(
                userRepository,
                visitRepository,
                mock(PatientRepository.class),
                shiftAssignmentService,
                mock(ClinicalNoteRepository.class),
                mock(DiagnosisRepository.class),
                mock(InvestigationRepository.class),
                handoverReportRepository,
                clinicalAlertRepository,
                sepsisScreeningRepository,
                fastTrackActivationRepository,
                hypoglycemiaEventRepository,
                infectionScreeningRepository,
                pathwayActivationRepository,
                labOrderRepository,
                clinicalDocumentRepository,
                informedConsentRepository,
                referralRepository,
                mohReportRepository,
                medicationSafetyCheckRepository,
                ioTDeviceRepository);
    }

    @Test
    void canAccessLabOrder_deniesUnknownOrder() {
        UUID missing = UUID.randomUUID();
        when(labOrderRepository.findVisitIdById(missing)).thenReturn(java.util.Optional.empty());
        assertFalse(authz.canAccessLabOrder(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("u", "p"),
                missing));
    }

    @Test
    void canAccessPathwayActivation_deniesUnknownActivation() {
        UUID missing = UUID.randomUUID();
        when(pathwayActivationRepository.findVisitIdById(missing)).thenReturn(java.util.Optional.empty());
        assertFalse(authz.canAccessPathwayActivation(
                authFor(user(Role.DOCTOR, null, hospitalId)), missing));
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

    // ── canReadHospitalAlerts (gates the operational alert feed → Alert Center) ──

    @Test
    void canReadHospitalAlerts_allowsDoctorAtOwnHospital() {
        // Regression: a roaming doctor was silently denied the hospital-wide alert
        // feed, so the Alert Center showed a falsely-reassuring "All Clear".
        assertTrue(authz.canReadHospitalAlerts(
                authFor(user(Role.DOCTOR, null, hospitalId)), hospitalId));
    }

    @Test
    void canReadHospitalAlerts_deniesDoctorAtOtherHospital() {
        assertFalse(authz.canReadHospitalAlerts(
                authFor(user(Role.DOCTOR, null, UUID.randomUUID())), hospitalId));
    }

    @Test
    void canReadHospitalAlerts_deniesHospitalAdmin() {
        assertFalse(authz.canReadHospitalAlerts(
                authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), hospitalId));
    }

    @Test
    void canReadHospitalAlerts_allowsRegularNurseAtOwnHospital() {
        // Parity fix: a regular (non-charge) nurse ALREADY receives these alerts
        // live over /topic/alerts/{hospitalId} (gated by canAccessHospital), so
        // denying the historical REST read here made the Alert Center flip between
        // showing live pushes and a false "feed unavailable". The read now matches
        // the live gate.
        assertTrue(authz.canReadHospitalAlerts(
                authFor(user(Role.NURSE, null, hospitalId)), hospitalId));
    }

    @Test
    void canReadHospitalAlerts_allowsLabTechAtOwnHospital() {
        assertTrue(authz.canReadHospitalAlerts(
                authFor(user(Role.LAB_TECHNICIAN, null, hospitalId)), hospitalId));
    }

    @Test
    void canReadHospitalAlerts_deniesNurseAtOtherHospital() {
        // Cross-hospital read stays denied — parity does not mean cross-tenant.
        assertFalse(authz.canReadHospitalAlerts(
                authFor(user(Role.NURSE, null, UUID.randomUUID())), hospitalId));
    }

    @Test
    void canReadHospitalAlerts_allowsSuperAdminAnyHospital() {
        assertTrue(authz.canReadHospitalAlerts(
                authFor(user(Role.SUPER_ADMIN, null, null)), hospitalId));
    }

    // ── canReceiveZoneAlerts (WebSocket zone-topic SUBSCRIBE scoping) ──

    @Test
    void canReceiveZoneAlerts_allowsNurseAssignedToThatZone_deniesOtherZone() {
        User nurse = user(Role.NURSE, null, hospitalId);
        // Regular nurse, not a shift lead → canSeeAllZonesAtHospital is false.
        when(shiftAssignmentService.isUserCurrentShiftLead(nurse.getId(), hospitalId)).thenReturn(false);
        com.smartTriage.smartTriage_server.module.shift.dto.ShiftAssignmentResponse sa =
                mock(com.smartTriage.smartTriage_server.module.shift.dto.ShiftAssignmentResponse.class);
        when(sa.getHospitalId()).thenReturn(hospitalId);
        when(sa.getZone()).thenReturn(com.smartTriage.smartTriage_server.common.enums.EdZone.GENERAL);
        when(sa.getAdditionalZones()).thenReturn(null);
        when(shiftAssignmentService.getCurrentShiftForUser(nurse.getId()))
                .thenReturn(java.util.Optional.of(sa));

        assertTrue(authz.canReceiveZoneAlerts(authFor(nurse), hospitalId,
                com.smartTriage.smartTriage_server.common.enums.EdZone.GENERAL));
        // The crux: a General-zone nurse may NOT receive ACUTE alerts.
        assertFalse(authz.canReceiveZoneAlerts(authFor(nurse), hospitalId,
                com.smartTriage.smartTriage_server.common.enums.EdZone.ACUTE));
    }

    @Test
    void canReceiveZoneAlerts_allowsOversightForAnyZone() {
        // Charge Nurse (NURSE + CHARGE_NURSE designation) → canSeeAllZonesAtHospital true.
        User chargeNurse = user(Role.NURSE, Designation.CHARGE_NURSE, hospitalId);
        assertTrue(authz.canReceiveZoneAlerts(authFor(chargeNurse), hospitalId,
                com.smartTriage.smartTriage_server.common.enums.EdZone.ACUTE));
    }

    // ── canAccessMedicationSafetyCheck (scopes the medication-safety OVERRIDE endpoint) ──

    @Test
    void canAccessMedicationSafetyCheck_deniesUnknownCheck() {
        UUID missing = UUID.randomUUID();
        when(medicationSafetyCheckRepository.findVisitIdById(missing)).thenReturn(Optional.empty());
        assertFalse(authz.canAccessMedicationSafetyCheck(
                authFor(user(Role.DOCTOR, null, hospitalId)), missing));
    }

    @Test
    void canAccessMedicationSafetyCheck_deniesAnotherHospitalsCheck() {
        // The check belongs to a visit at ANOTHER hospital — a doctor at `hospitalId`
        // must not be able to clear that hospital's safety block by enumerating a checkId.
        UUID checkId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        UUID otherHospital = UUID.randomUUID();
        when(medicationSafetyCheckRepository.findVisitIdById(checkId)).thenReturn(Optional.of(visitId));
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(otherHospital));
        assertFalse(authz.canAccessMedicationSafetyCheck(
                authFor(user(Role.DOCTOR, null, hospitalId)), checkId));
    }

    @Test
    void canAccessMedicationSafetyCheck_allowsOwnHospitalCheck() {
        UUID checkId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        when(medicationSafetyCheckRepository.findVisitIdById(checkId)).thenReturn(Optional.of(visitId));
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(hospitalId));
        assertTrue(authz.canAccessMedicationSafetyCheck(
                authFor(user(Role.DOCTOR, null, hospitalId)), checkId));
        // SUPER_ADMIN may override at any hospital.
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(UUID.randomUUID()));
        assertTrue(authz.canAccessMedicationSafetyCheck(
                authFor(user(Role.SUPER_ADMIN, null, null)), checkId));
    }

    // ── canAccessDocument (clinical document GET-by-id, #3 cross-hospital fix) ──

    @Test
    void canAccessDocument_deniesUnknownDocument() {
        UUID missing = UUID.randomUUID();
        when(clinicalDocumentRepository.findVisitIdById(missing)).thenReturn(Optional.empty());
        assertFalse(authz.canAccessDocument(
                authFor(user(Role.DOCTOR, null, hospitalId)), missing));
    }

    @Test
    void canAccessDocument_deniesAnotherHospitalsDocument() {
        UUID docId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        UUID otherHospital = UUID.randomUUID();
        when(clinicalDocumentRepository.findVisitIdById(docId)).thenReturn(Optional.of(visitId));
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(otherHospital));
        // Caller is a doctor at `hospitalId`; the document's visit is at otherHospital.
        assertFalse(authz.canAccessDocument(
                authFor(user(Role.DOCTOR, null, hospitalId)), docId));
    }

    @Test
    void canAccessDocument_allowsOwnHospitalDocument() {
        UUID docId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        when(clinicalDocumentRepository.findVisitIdById(docId)).thenReturn(Optional.of(visitId));
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(hospitalId));
        org.junit.jupiter.api.Assertions.assertTrue(authz.canAccessDocument(
                authFor(user(Role.DOCTOR, null, hospitalId)), docId));
    }

    @Test
    void canAccessConsent_deniesUnknownConsent() {
        UUID missing = UUID.randomUUID();
        when(informedConsentRepository.findVisitIdById(missing)).thenReturn(Optional.empty());
        assertFalse(authz.canAccessConsent(
                authFor(user(Role.DOCTOR, null, hospitalId)), missing));
    }

    @Test
    void canAccessConsent_deniesAnotherHospitalsConsent() {
        UUID consentId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        UUID otherHospital = UUID.randomUUID();
        when(informedConsentRepository.findVisitIdById(consentId)).thenReturn(Optional.of(visitId));
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(otherHospital));
        assertFalse(authz.canAccessConsent(
                authFor(user(Role.DOCTOR, null, hospitalId)), consentId));
    }

    @Test
    void canAccessReferral_deniesUnknownReferral() {
        UUID missing = UUID.randomUUID();
        when(referralRepository.findVisitIdById(missing)).thenReturn(Optional.empty());
        assertFalse(authz.canAccessReferral(
                authFor(user(Role.DOCTOR, null, hospitalId)), missing));
    }

    @Test
    void canAccessReferral_deniesAnotherHospitalsReferral() {
        UUID referralId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        UUID otherHospital = UUID.randomUUID();
        when(referralRepository.findVisitIdById(referralId)).thenReturn(Optional.of(visitId));
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(otherHospital));
        assertFalse(authz.canAccessReferral(
                authFor(user(Role.DOCTOR, null, hospitalId)), referralId));
    }

    // ── canViewHospitalReports (R1: quality/aggregate reporting read gate) ──

    @Test
    void canViewHospitalReports_allowsHospitalAdminAndReadOnly_atOwnHospital() {
        org.junit.jupiter.api.Assertions.assertTrue(authz.canViewHospitalReports(
                authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), hospitalId));
        org.junit.jupiter.api.Assertions.assertTrue(authz.canViewHospitalReports(
                authFor(user(Role.READ_ONLY, null, hospitalId)), hospitalId));
    }

    @Test
    void canViewHospitalReports_deniesBedsideClinicians() {
        assertFalse(authz.canViewHospitalReports(authFor(user(Role.DOCTOR, null, hospitalId)), hospitalId));
        assertFalse(authz.canViewHospitalReports(authFor(user(Role.NURSE, null, hospitalId)), hospitalId));
        assertFalse(authz.canViewHospitalReports(authFor(user(Role.LAB_TECHNICIAN, null, hospitalId)), hospitalId));
        assertFalse(authz.canViewHospitalReports(authFor(user(Role.REGISTRAR, null, hospitalId)), hospitalId));
    }

    @Test
    void canViewHospitalReports_deniesAdminFromAnotherHospital() {
        assertFalse(authz.canViewHospitalReports(
                authFor(user(Role.HOSPITAL_ADMIN, null, UUID.randomUUID())), hospitalId));
    }

    // ── canReadHandoverReport (R1: clinical-role gate on SBAR read/PDF) ──

    @Test
    void canReadHandoverReport_deniesNonClinicalRoles_beforeAnyLookup() {
        // No repo stub needed — the clinical-role check fails first.
        assertFalse(authz.canReadHandoverReport(authFor(user(Role.REGISTRAR, null, hospitalId)), UUID.randomUUID()));
        assertFalse(authz.canReadHandoverReport(authFor(user(Role.LAB_TECHNICIAN, null, hospitalId)), UUID.randomUUID()));
        assertFalse(authz.canReadHandoverReport(authFor(user(Role.READ_ONLY, null, hospitalId)), UUID.randomUUID()));
    }

    @Test
    void canReadHandoverReport_allowsClinicianAtOwnHospital() {
        UUID reportId = UUID.randomUUID();
        when(handoverReportRepository.findHospitalIdByReportId(reportId)).thenReturn(Optional.of(hospitalId));
        org.junit.jupiter.api.Assertions.assertTrue(authz.canReadHandoverReport(
                authFor(user(Role.DOCTOR, null, hospitalId)), reportId));
    }

    @Test
    void canReadHandoverReport_deniesClinicianFromAnotherHospital() {
        UUID reportId = UUID.randomUUID();
        when(handoverReportRepository.findHospitalIdByReportId(reportId)).thenReturn(Optional.of(UUID.randomUUID()));
        assertFalse(authz.canReadHandoverReport(
                authFor(user(Role.DOCTOR, null, hospitalId)), reportId));
    }

    // ── canAccessVisit (the per-visit gate behind handover GENERATE, sepsis, etc.) ──

    @Test
    void canAccessVisit_allowsDoctorAtVisitHospital_deniesOtherHospital() {
        UUID visitId = UUID.randomUUID();
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(hospitalId));
        // Bedside doctor at the visit's hospital may generate a handover — no shift-lead authority needed.
        assertTrue(authz.canAccessVisit(authFor(user(Role.DOCTOR, null, hospitalId)), visitId));
        // A doctor at a DIFFERENT hospital must not (cross-tenant).
        assertFalse(authz.canAccessVisit(authFor(user(Role.DOCTOR, null, UUID.randomUUID())), visitId));
    }

    @Test
    void canAccessVisit_deniesUnknownVisit_allowsSuperAdmin() {
        UUID unknownVisit = UUID.randomUUID();
        when(visitRepository.findHospitalIdByVisitId(unknownVisit)).thenReturn(Optional.empty());
        assertFalse(authz.canAccessVisit(authFor(user(Role.DOCTOR, null, hospitalId)), unknownVisit));

        UUID knownVisit = UUID.randomUUID();
        when(visitRepository.findHospitalIdByVisitId(knownVisit)).thenReturn(Optional.of(UUID.randomUUID()));
        assertTrue(authz.canAccessVisit(authFor(user(Role.SUPER_ADMIN, null, null)), knownVisit));
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

    // ── canViewMohReport / canSubmitMohReport (R5 object-level authz) ──

    @Test
    void superAdminCanViewNationalReport() {
        UUID reportId = UUID.randomUUID();
        // National report — only SUPER_ADMIN; super-admin short-circuits before any lookup.
        assertTrue(authz.canViewMohReport(authFor(user(Role.SUPER_ADMIN, null, null)), reportId));
    }

    @Test
    void hospitalAdminAndReadOnlyCannotViewNationalReportById() {
        UUID reportId = UUID.randomUUID();
        when(mohReportRepository.findReportLevelById(reportId)).thenReturn(Optional.of(ReportLevel.NATIONAL));
        assertFalse(authz.canViewMohReport(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), reportId));
        assertFalse(authz.canViewMohReport(authFor(user(Role.READ_ONLY, null, hospitalId)), reportId));
    }

    @Test
    void hospitalAdminCanViewOwnHospitalReportButNotAnotherHospitals() {
        UUID reportId = UUID.randomUUID();
        when(mohReportRepository.findReportLevelById(reportId)).thenReturn(Optional.of(ReportLevel.HOSPITAL));
        when(mohReportRepository.findHospitalIdById(reportId)).thenReturn(Optional.of(hospitalId));
        assertTrue(authz.canViewMohReport(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), reportId));
        // A HOSPITAL_ADMIN at a DIFFERENT hospital must not read this hospital's report by id.
        assertFalse(authz.canViewMohReport(authFor(user(Role.HOSPITAL_ADMIN, null, UUID.randomUUID())), reportId));
    }

    @Test
    void canViewMohReport_deniesUnknownReportForNonSuperAdmin() {
        UUID reportId = UUID.randomUUID();
        when(mohReportRepository.findReportLevelById(reportId)).thenReturn(Optional.empty());
        assertFalse(authz.canViewMohReport(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), reportId));
    }

    @Test
    void hospitalAdminCannotSubmitNationalReport_butSuperAdminCan() {
        UUID reportId = UUID.randomUUID();
        when(mohReportRepository.findReportLevelById(reportId)).thenReturn(Optional.of(ReportLevel.NATIONAL));
        assertFalse(authz.canSubmitMohReport(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), reportId));
        // SUPER_ADMIN short-circuits — national submission is the national governance owner's job.
        assertTrue(authz.canSubmitMohReport(authFor(user(Role.SUPER_ADMIN, null, null)), reportId));
    }

    @Test
    void hospitalAdminCanSubmitOwnHospitalReportOnly() {
        UUID reportId = UUID.randomUUID();
        when(mohReportRepository.findReportLevelById(reportId)).thenReturn(Optional.of(ReportLevel.HOSPITAL));
        when(mohReportRepository.findHospitalIdById(reportId)).thenReturn(Optional.of(hospitalId));
        assertTrue(authz.canSubmitMohReport(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), reportId));
        assertFalse(authz.canSubmitMohReport(authFor(user(Role.HOSPITAL_ADMIN, null, UUID.randomUUID())), reportId));
        // READ_ONLY may read (elsewhere) but never advance lifecycle.
        assertFalse(authz.canSubmitMohReport(authFor(user(Role.READ_ONLY, null, hospitalId)), reportId));
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

    // ── canManageDataSharingConsent (Phase 2 — who may record/withdraw consent) ──

    @Test
    void registrationCapableRolesCanManageDataSharingConsent() {
        // Registrar captures the opt-in at the desk; clinicians may too. Role-only (no hospital scope).
        assertTrue(authz.canManageDataSharingConsent(authFor(user(Role.SUPER_ADMIN, null, null))));
        assertTrue(authz.canManageDataSharingConsent(authFor(user(Role.DOCTOR, null, hospitalId))));
        assertTrue(authz.canManageDataSharingConsent(authFor(user(Role.NURSE, null, hospitalId))));
        assertTrue(authz.canManageDataSharingConsent(authFor(user(Role.REGISTRAR, null, hospitalId))));
    }

    @Test
    void nonRegistrationRolesCannotManageDataSharingConsent() {
        assertFalse(authz.canManageDataSharingConsent(authFor(user(Role.PARAMEDIC, null, hospitalId))));
        assertFalse(authz.canManageDataSharingConsent(authFor(user(Role.LAB_TECHNICIAN, null, hospitalId))));
        assertFalse(authz.canManageDataSharingConsent(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId))));
        assertFalse(authz.canManageDataSharingConsent(authFor(user(Role.READ_ONLY, null, hospitalId))));
        assertFalse(authz.canManageDataSharingConsent(null));
        assertFalse(authz.canManageDataSharingConsent(
                new UsernamePasswordAuthenticationToken("not-a-user", null)));
    }

    // ── canAccessCrossHospitalDeepRecord (Phase 2 — who may ATTEMPT a deep read) ──

    @Test
    void treatingCliniciansCanAttemptCrossHospitalDeepRecord() {
        // REGISTRAR excluded — the deep clinical record is not a registration need.
        assertTrue(authz.canAccessCrossHospitalDeepRecord(authFor(user(Role.SUPER_ADMIN, null, null))));
        assertTrue(authz.canAccessCrossHospitalDeepRecord(authFor(user(Role.DOCTOR, null, hospitalId))));
        assertTrue(authz.canAccessCrossHospitalDeepRecord(authFor(user(Role.NURSE, null, hospitalId))));
        assertTrue(authz.canAccessCrossHospitalDeepRecord(authFor(user(Role.PARAMEDIC, null, hospitalId))));
    }

    @Test
    void nonTreatingRolesCannotAttemptCrossHospitalDeepRecord() {
        assertFalse(authz.canAccessCrossHospitalDeepRecord(authFor(user(Role.REGISTRAR, null, hospitalId))));
        assertFalse(authz.canAccessCrossHospitalDeepRecord(authFor(user(Role.LAB_TECHNICIAN, null, hospitalId))));
        assertFalse(authz.canAccessCrossHospitalDeepRecord(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId))));
        assertFalse(authz.canAccessCrossHospitalDeepRecord(authFor(user(Role.READ_ONLY, null, hospitalId))));
        assertFalse(authz.canAccessCrossHospitalDeepRecord(null));
        assertFalse(authz.canAccessCrossHospitalDeepRecord(
                new UsernamePasswordAuthenticationToken("not-a-user", null)));
    }

    // ── canAccessRegistrarReports (R11 — front-desk operational reporting) ──

    @Test
    void canAccessRegistrarReports_allowsRegistrarAndHospitalAdmin_atOwnHospital() {
        assertTrue(authz.canAccessRegistrarReports(
                authFor(user(Role.REGISTRAR, null, hospitalId)), hospitalId));
        assertTrue(authz.canAccessRegistrarReports(
                authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), hospitalId));
    }

    @Test
    void canAccessRegistrarReports_allowsSuperAdminAnyHospital() {
        // SUPER_ADMIN short-circuits before the hospital-scope check.
        assertTrue(authz.canAccessRegistrarReports(
                authFor(user(Role.SUPER_ADMIN, null, null)), hospitalId));
    }

    @Test
    void canAccessRegistrarReports_deniesClinicalAndReadOnlyRoles() {
        // Operational desk reporting is NOT a clinical-role need. READ_ONLY is the
        // governance auditor (canViewHospitalReports) — deliberately NOT this audience.
        assertFalse(authz.canAccessRegistrarReports(authFor(user(Role.DOCTOR, null, hospitalId)), hospitalId));
        assertFalse(authz.canAccessRegistrarReports(authFor(user(Role.NURSE, null, hospitalId)), hospitalId));
        assertFalse(authz.canAccessRegistrarReports(authFor(user(Role.LAB_TECHNICIAN, null, hospitalId)), hospitalId));
        assertFalse(authz.canAccessRegistrarReports(authFor(user(Role.PARAMEDIC, null, hospitalId)), hospitalId));
        assertFalse(authz.canAccessRegistrarReports(authFor(user(Role.READ_ONLY, null, hospitalId)), hospitalId));
    }

    @Test
    void canAccessRegistrarReports_deniesRegistrarFromAnotherHospital() {
        assertFalse(authz.canAccessRegistrarReports(
                authFor(user(Role.REGISTRAR, null, UUID.randomUUID())), hospitalId));
        assertFalse(authz.canAccessRegistrarReports(
                authFor(user(Role.HOSPITAL_ADMIN, null, UUID.randomUUID())), hospitalId));
    }

    @Test
    void canAccessRegistrarReports_deniesNullAuthOrPrincipalOrHospital() {
        assertFalse(authz.canAccessRegistrarReports(null, hospitalId));
        assertFalse(authz.canAccessRegistrarReports(
                new UsernamePasswordAuthenticationToken("not-a-user", null), hospitalId));
        assertFalse(authz.canAccessRegistrarReports(
                authFor(user(Role.REGISTRAR, null, hospitalId)), null));
    }

    // ── canOperateRfidDevice (V95 — scopes the RFID bind-mode endpoint to the device's hospital) ──

    @Test
    void canOperateRfidDevice_allowsRegistrarAndHospitalAdmin_atDeviceHospital() {
        UUID deviceId = UUID.randomUUID();
        when(ioTDeviceRepository.findHospitalIdById(deviceId)).thenReturn(Optional.of(hospitalId));
        assertTrue(authz.canOperateRfidDevice(authFor(user(Role.REGISTRAR, null, hospitalId)), deviceId));
        assertTrue(authz.canOperateRfidDevice(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), deviceId));
    }

    @Test
    void canOperateRfidDevice_allowsSuperAdmin_withoutDeviceLookup() {
        // SUPER_ADMIN short-circuits before resolving the device.
        assertTrue(authz.canOperateRfidDevice(authFor(user(Role.SUPER_ADMIN, null, null)), UUID.randomUUID()));
    }

    @Test
    void canOperateRfidDevice_deniesAnotherHospitalsRegistrar_andClinicalRoles() {
        UUID deviceId = UUID.randomUUID();
        when(ioTDeviceRepository.findHospitalIdById(deviceId)).thenReturn(Optional.of(hospitalId));
        // Registrar at a different hospital cannot drive this hospital's reader by id.
        assertFalse(authz.canOperateRfidDevice(
                authFor(user(Role.REGISTRAR, null, UUID.randomUUID())), deviceId));
        // Clinical roles are not the registration-desk audience.
        assertFalse(authz.canOperateRfidDevice(authFor(user(Role.DOCTOR, null, hospitalId)), deviceId));
        assertFalse(authz.canOperateRfidDevice(authFor(user(Role.NURSE, null, hospitalId)), deviceId));
    }

    @Test
    void canOperateRfidDevice_deniesUnknownDevice_andNullArgs() {
        UUID deviceId = UUID.randomUUID();
        when(ioTDeviceRepository.findHospitalIdById(deviceId)).thenReturn(Optional.empty());
        assertFalse(authz.canOperateRfidDevice(authFor(user(Role.REGISTRAR, null, hospitalId)), deviceId));
        assertFalse(authz.canOperateRfidDevice(null, deviceId));
        assertFalse(authz.canOperateRfidDevice(authFor(user(Role.REGISTRAR, null, hospitalId)), null));
    }

    // ── callerCanConfirmFieldTriage (EMS field-triage confirmation — who may accept the
    //    paramedic's RED/ORANGE category on arrival WITHOUT re-running the full form) ──

    /** Wire up a doctor/nurse's current shift so their zone coverage resolves. */
    private void assignShift(User user, UUID atHospital,
                             com.smartTriage.smartTriage_server.common.enums.EdZone zone,
                             java.util.Set<com.smartTriage.smartTriage_server.common.enums.EdZone> additional) {
        when(shiftAssignmentService.isUserCurrentShiftLead(user.getId(), atHospital)).thenReturn(false);
        com.smartTriage.smartTriage_server.module.shift.dto.ShiftAssignmentResponse sa =
                mock(com.smartTriage.smartTriage_server.module.shift.dto.ShiftAssignmentResponse.class);
        lenient().when(sa.getHospitalId()).thenReturn(atHospital);
        lenient().when(sa.getZone()).thenReturn(zone);
        lenient().when(sa.getAdditionalZones()).thenReturn(additional);
        when(shiftAssignmentService.getCurrentShiftForUser(user.getId()))
                .thenReturn(java.util.Optional.of(sa));
    }

    @Test
    void callerCanConfirmFieldTriage_allowsTriageAuthority_evenWithoutZoneMatch() {
        // A shift-lead nurse is a triage authority → may confirm anywhere; no visit-zone lookup needed.
        UUID visitId = UUID.randomUUID();
        User nurse = user(Role.NURSE, Designation.STAFF_NURSE, hospitalId);
        when(shiftAssignmentService.isUserCurrentShiftLead(nurse.getId(), hospitalId)).thenReturn(true);
        assertTrue(authz.callerCanConfirmFieldTriage(authFor(nurse), visitId));
    }

    @Test
    void callerCanConfirmFieldTriage_allowsReceivingZoneDoctor_andNurseViaAdditionalZone() {
        UUID visitId = UUID.randomUUID();
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(hospitalId));
        when(visitRepository.findCurrentEdZoneByVisitId(visitId))
                .thenReturn(Optional.of(com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS));

        // Doctor whose PRIMARY shift zone is RESUS (the receiving team) — not a triage authority.
        User doctor = user(Role.DOCTOR, null, hospitalId);
        assignShift(doctor, hospitalId,
                com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS, null);
        assertTrue(authz.callerCanConfirmFieldTriage(authFor(doctor), visitId));

        // Nurse who covers RESUS as an ADDITIONAL zone this shift.
        User nurse = user(Role.NURSE, null, hospitalId);
        assignShift(nurse, hospitalId,
                com.smartTriage.smartTriage_server.common.enums.EdZone.GENERAL,
                java.util.Set.of(com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS));
        assertTrue(authz.callerCanConfirmFieldTriage(authFor(nurse), visitId));
    }

    @Test
    void callerCanConfirmFieldTriage_deniesClinicianNotCoveringThePatientsZone() {
        UUID visitId = UUID.randomUUID();
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(hospitalId));
        when(visitRepository.findCurrentEdZoneByVisitId(visitId))
                .thenReturn(Optional.of(com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS));
        // Doctor working GENERAL this shift — not the receiving team for a RESUS arrival.
        User doctor = user(Role.DOCTOR, null, hospitalId);
        assignShift(doctor, hospitalId,
                com.smartTriage.smartTriage_server.common.enums.EdZone.GENERAL, null);
        assertFalse(authz.callerCanConfirmFieldTriage(authFor(doctor), visitId));
    }

    @Test
    void callerCanConfirmFieldTriage_deniesNonBedsideRoles() {
        UUID visitId = UUID.randomUUID();
        // Even with a placed zone, admins / registrars / paramedics / read-only never confirm triage.
        lenient().when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(hospitalId));
        lenient().when(visitRepository.findCurrentEdZoneByVisitId(visitId))
                .thenReturn(Optional.of(com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS));
        assertFalse(authz.callerCanConfirmFieldTriage(authFor(user(Role.HOSPITAL_ADMIN, null, hospitalId)), visitId));
        assertFalse(authz.callerCanConfirmFieldTriage(authFor(user(Role.REGISTRAR, null, hospitalId)), visitId));
        assertFalse(authz.callerCanConfirmFieldTriage(authFor(user(Role.PARAMEDIC, null, hospitalId)), visitId));
        assertFalse(authz.callerCanConfirmFieldTriage(authFor(user(Role.READ_ONLY, null, hospitalId)), visitId));
        // SUPER_ADMIN is not a bedside clinician either — confirmation is a clinical act.
        assertFalse(authz.callerCanConfirmFieldTriage(authFor(user(Role.SUPER_ADMIN, null, null)), visitId));
    }

    @Test
    void callerCanConfirmFieldTriage_deniesUnplacedVisit_crossHospital_andNullArgs() {
        // Unplaced (zone null) → no "receiving team" to attest → deny (the trio path already ran).
        UUID unplaced = UUID.randomUUID();
        when(visitRepository.findHospitalIdByVisitId(unplaced)).thenReturn(Optional.of(hospitalId));
        when(visitRepository.findCurrentEdZoneByVisitId(unplaced)).thenReturn(Optional.empty());
        User doctor = user(Role.DOCTOR, null, hospitalId);
        assignShift(doctor, hospitalId,
                com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS, null);
        assertFalse(authz.callerCanConfirmFieldTriage(authFor(doctor), unplaced));

        // Cross-hospital: a doctor at another hospital cannot confirm this hospital's arrival —
        // the belongs-to-hospital check inside canReceiveZoneAlerts denies before any zone match.
        UUID visitId = UUID.randomUUID();
        when(visitRepository.findHospitalIdByVisitId(visitId)).thenReturn(Optional.of(hospitalId));
        when(visitRepository.findCurrentEdZoneByVisitId(visitId))
                .thenReturn(Optional.of(com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS));
        User otherHospDoctor = user(Role.DOCTOR, null, UUID.randomUUID());
        assertFalse(authz.callerCanConfirmFieldTriage(authFor(otherHospDoctor), visitId));

        // Null visitId denied.
        assertFalse(authz.callerCanConfirmFieldTriage(authFor(doctor), null));
    }
}
