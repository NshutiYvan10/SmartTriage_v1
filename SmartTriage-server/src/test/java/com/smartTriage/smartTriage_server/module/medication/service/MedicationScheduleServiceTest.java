package com.smartTriage.smartTriage_server.module.medication.service;

import com.smartTriage.smartTriage_server.common.enums.AllergySeverity;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.DoseKind;
import com.smartTriage.smartTriage_server.common.enums.DoseStatus;
import com.smartTriage.smartTriage_server.common.enums.MedicationProductType;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.VitalGateComparator;
import com.smartTriage.smartTriage_server.common.enums.VitalGateParameter;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.ApproveOrderRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.RecordPrnDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository;
import com.smartTriage.smartTriage_server.module.medsafety.engine.MedicationSafetyEngine;
import com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the V67 dose workflow — the safety-critical paths:
 * schedule roll-forward / completion, administration-time verification
 * (dose match, witness, allergy recheck), PRN gating (minimum interval,
 * 24-hour cap, vitals gate fail-closed), and the high-alert approval
 * gate's separation of duties.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MedicationScheduleServiceTest {

    @Mock private MedicationAdministrationRepository medicationRepository;
    @Mock private MedicationDoseRepository doseRepository;
    @Mock private ClinicalAlertRepository clinicalAlertRepository;
    @Mock private VitalSignsRepository vitalSignsRepository;
    @Mock private MedicationSafetyCheckRepository medicationSafetyCheckRepository;
    @Mock private MedicationSafetyEngine medicationSafetyEngine;
    @Mock private RealTimeEventPublisher realTimeEventPublisher;
    @Mock private MedicationService medicationService;

    @InjectMocks private MedicationScheduleService service;

    private Hospital hospital;
    private Patient patient;
    private Visit visit;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Test");
        patient.setLastName("Patient");
        patient.setHospital(hospital);

        visit = new Visit();
        visit.setId(UUID.randomUUID());
        visit.setVisitNumber("V-001");
        visit.setPatient(patient);
        visit.setHospital(hospital);

        // Repos echo back what they save; engine finds no allergy; no
        // unresolved safety block. Individual tests override as needed.
        when(doseRepository.save(any(MedicationDose.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(medicationRepository.save(any(MedicationAdministration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(medicationSafetyEngine.assessAllergyForPrescription(any(), any(), anyString()))
                .thenReturn(MedicationSafetyEngine.AllergyAssessment.none());
        when(medicationSafetyCheckRepository
                .findByMedicationIdAndIsActiveTrueOrderByCheckedAtDesc(any()))
                .thenReturn(Optional.empty());
        when(doseRepository.countByMedicationIdAndIsActiveTrue(any())).thenReturn(1L);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private MedicationAdministration order(PrescriptionType type) {
        MedicationAdministration med = MedicationAdministration.builder()
                .visit(visit)
                .drugName("Ceftriaxone")
                .route(MedicationRoute.IV)
                .prescribedAt(Instant.now().minus(Duration.ofHours(1)))
                .prescribedByName("Dr Order")
                .status(MedicationStatus.PRESCRIBED)
                .prescriptionType(type)
                .productType(MedicationProductType.DRUG)
                .build();
        med.setId(UUID.randomUUID());
        return med;
    }

    private MedicationDose dueDose(MedicationAdministration med, Instant dueAt, int seq) {
        MedicationDose dose = MedicationDose.builder()
                .medication(med)
                .visit(visit)
                .kind(med.effectiveType() == PrescriptionType.SCHEDULED
                        ? DoseKind.SCHEDULED_DOSE : DoseKind.ONE_TIME_DOSE)
                .status(DoseStatus.DUE)
                .sequenceNumber(seq)
                .dueAt(dueAt)
                .build();
        dose.setId(UUID.randomUUID());
        when(doseRepository.findByIdAndIsActiveTrue(dose.getId())).thenReturn(Optional.of(dose));
        return dose;
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private User user(Role role, Designation designation) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName("Nurse");
        u.setLastName("Test");
        u.setEmail("nurse@test.rw");
        u.setRole(role);
        u.setDesignation(designation);
        return u;
    }

    // ════════════════════════════════════════════════════════════════
    // Schedule roll-forward
    // ════════════════════════════════════════════════════════════════

    @Test
    void rollScheduleForward_createsNextDose_atAnchorPlusInterval() {
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        med.setIntervalHours(8.0);
        when(doseRepository.countByMedicationIdAndStatusAndIsActiveTrue(
                med.getId(), DoseStatus.GIVEN)).thenReturn(1L);

        Instant anchor = Instant.parse("2026-06-11T08:00:00Z");
        service.rollScheduleForward(med, anchor);

        ArgumentCaptor<MedicationDose> captor = ArgumentCaptor.forClass(MedicationDose.class);
        verify(doseRepository).save(captor.capture());
        MedicationDose next = captor.getValue();
        assertEquals(DoseStatus.DUE, next.getStatus());
        assertEquals(DoseKind.SCHEDULED_DOSE, next.getKind());
        assertEquals(anchor.plus(Duration.ofHours(8)), next.getDueAt());
    }

    @Test
    void rollScheduleForward_completesOrder_whenMaxDosesReached() {
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        med.setIntervalHours(8.0);
        med.setMaxDoses(6);
        when(doseRepository.countByMedicationIdAndStatusAndIsActiveTrue(
                med.getId(), DoseStatus.GIVEN)).thenReturn(6L);

        service.rollScheduleForward(med, Instant.now());

        assertEquals(MedicationStatus.COMPLETED, med.getStatus());
        assertNotNull(med.getCompletedAt());
        // No new DUE dose is created for a completed order.
        verify(doseRepository, never()).save(any(MedicationDose.class));
    }

    @Test
    void rollScheduleForward_completesOrder_whenNextDoseWouldPassEndAt() {
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        med.setIntervalHours(12.0);
        med.setEndAt(Instant.now().plus(Duration.ofHours(6))); // next dose (+12h) overshoots
        when(doseRepository.countByMedicationIdAndStatusAndIsActiveTrue(
                med.getId(), DoseStatus.GIVEN)).thenReturn(2L);

        service.rollScheduleForward(med, Instant.now());

        assertEquals(MedicationStatus.COMPLETED, med.getStatus());
        verify(doseRepository, never()).save(any(MedicationDose.class));
    }

    // ════════════════════════════════════════════════════════════════
    // Administer a DUE dose
    // ════════════════════════════════════════════════════════════════

    @Test
    void administerDose_rejectsNonDueDose() {
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        MedicationDose dose = dueDose(med, Instant.now(), 1);
        dose.setStatus(DoseStatus.GIVEN);

        assertThrows(ClinicalBusinessException.class,
                () -> service.administerDose(dose.getId(), new AdministerDoseRequest()));
    }

    @Test
    void administerDose_blocksDoseMismatch_withoutOverride() {
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        med.setDoseValue(new BigDecimal("1000"));
        med.setDoseUnit("mg");
        MedicationDose dose = dueDose(med, Instant.now(), 1);

        AdministerDoseRequest req = AdministerDoseRequest.builder()
                .doseValue(new BigDecimal("100")) // 10× off — classic decimal slip
                .doseUnit("mg")
                .build();

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.administerDose(dose.getId(), req));
        assertTrue(ex.getMessage().contains("Dose verification failed"));
        assertEquals(DoseStatus.DUE, dose.getStatus()); // nothing recorded
    }

    @Test
    void administerDose_allowsDoseMismatch_withJustifiedOverride_andRaisesAlert() {
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        med.setIntervalHours(8.0);
        med.setDoseValue(new BigDecimal("1000"));
        med.setDoseUnit("mg");
        MedicationDose dose = dueDose(med, Instant.now(), 1);

        AdministerDoseRequest req = AdministerDoseRequest.builder()
                .doseValue(new BigDecimal("500"))
                .doseUnit("mg")
                .override(true)
                .overrideJustification("Half dose per consultant instruction — renal impairment")
                .build();

        service.administerDose(dose.getId(), req);

        assertEquals(DoseStatus.GIVEN, dose.getStatus());
        assertTrue(dose.isOverride());
        assertEquals(new BigDecimal("500"), dose.getDoseValue());
        // Department-visible override alert.
        verify(clinicalAlertRepository, atLeastOnce()).save(any(ClinicalAlert.class));
    }

    @Test
    void administerDose_requiresWitness_whenOrderDemandsIt() {
        MedicationAdministration med = order(PrescriptionType.ONE_TIME);
        med.setRequiresWitness(true);
        MedicationDose dose = dueDose(med, Instant.now(), 1);

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.administerDose(dose.getId(), new AdministerDoseRequest()));
        assertTrue(ex.getMessage().toLowerCase().contains("witness"));
    }

    @Test
    void administerDose_oneTime_closesOutTheOrder() {
        MedicationAdministration med = order(PrescriptionType.ONE_TIME);
        MedicationDose dose = dueDose(med, Instant.now(), 1);

        service.administerDose(dose.getId(), new AdministerDoseRequest());

        assertEquals(DoseStatus.GIVEN, dose.getStatus());
        assertEquals(MedicationStatus.ADMINISTERED, med.getStatus());
        assertNotNull(med.getAdministeredAt());
    }

    @Test
    void administerDose_scheduled_rollsTheScheduleForward() {
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        med.setIntervalHours(6.0);
        when(doseRepository.countByMedicationIdAndStatusAndIsActiveTrue(
                med.getId(), DoseStatus.GIVEN)).thenReturn(1L);
        MedicationDose dose = dueDose(med, Instant.now(), 1);

        service.administerDose(dose.getId(), new AdministerDoseRequest());

        // Two saves: the given dose + the next DUE dose.
        ArgumentCaptor<MedicationDose> captor = ArgumentCaptor.forClass(MedicationDose.class);
        verify(doseRepository, atLeastOnce()).save(captor.capture());
        MedicationDose next = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(DoseStatus.DUE, next.getStatus());
        assertEquals(dose.getGivenAt().plus(Duration.ofHours(6)), next.getDueAt());
    }

    @Test
    void administerDose_blocksOnAllergyRecordedAfterPrescribing() {
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        MedicationDose dose = dueDose(med, Instant.now(), 1);
        when(medicationSafetyEngine.assessAllergyForPrescription(any(), any(), anyString()))
                .thenReturn(new MedicationSafetyEngine.AllergyAssessment(
                        true, AllergySeverity.ANAPHYLAXIS, "ceftriaxone",
                        "airway swelling", "ALLERGY ALERT (ANAPHYLAXIS): direct match"));

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.administerDose(dose.getId(), new AdministerDoseRequest()));
        assertTrue(ex.getMessage().contains("allergy"));
        assertEquals(DoseStatus.DUE, dose.getStatus());
    }

    @Test
    void administerDose_skipsAllergyRecheck_whenPrescriberAlreadyOverrode() {
        MedicationAdministration med = order(PrescriptionType.ONE_TIME);
        med.setPrescribedDespiteAllergy(true);
        MedicationDose dose = dueDose(med, Instant.now(), 1);
        // Even a blocking match must not stop the dose — the prescriber
        // already acknowledged it at prescribe time.
        when(medicationSafetyEngine.assessAllergyForPrescription(any(), any(), anyString()))
                .thenReturn(new MedicationSafetyEngine.AllergyAssessment(
                        true, AllergySeverity.SEVERE, "ceftriaxone", "rash", "match"));

        service.administerDose(dose.getId(), new AdministerDoseRequest());
        assertEquals(DoseStatus.GIVEN, dose.getStatus());
    }

    @Test
    void administerDose_enforcesSeparationOfDuties() {
        User prescriber = user(Role.DOCTOR, Designation.MEDICAL_OFFICER);
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        med.setPrescribedBy(prescriber);
        MedicationDose dose = dueDose(med, Instant.now(), 1);
        authenticateAs(prescriber); // same clinician tries to give it

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.administerDose(dose.getId(), new AdministerDoseRequest()));
        assertTrue(ex.getMessage().contains("Separation of duties"));
    }

    // ════════════════════════════════════════════════════════════════
    // PRN gating
    // ════════════════════════════════════════════════════════════════

    private MedicationAdministration prnOrder() {
        MedicationAdministration med = order(PrescriptionType.PRN);
        med.setDrugName("Paracetamol");
        med.setPrnIndication("pain");
        med.setPrnMinIntervalHours(6.0);
        med.setPrnMaxDosesPerDay(4);
        when(medicationRepository.findByIdAndIsActiveTrue(med.getId()))
                .thenReturn(Optional.of(med));
        return med;
    }

    private RecordPrnDoseRequest prnRequest() {
        return RecordPrnDoseRequest.builder().prnReason("pain 6/10").build();
    }

    @Test
    void prnDose_blockedWithinMinimumInterval() {
        MedicationAdministration med = prnOrder();
        MedicationDose lastGiven = MedicationDose.builder()
                .medication(med).visit(visit).kind(DoseKind.PRN_DOSE)
                .status(DoseStatus.GIVEN)
                .givenAt(Instant.now().minus(Duration.ofHours(2))) // < 6h ago
                .build();
        when(doseRepository.findFirstByMedicationIdAndStatusAndIsActiveTrueOrderByGivenAtDesc(
                med.getId(), DoseStatus.GIVEN)).thenReturn(Optional.of(lastGiven));
        when(doseRepository.countByMedicationIdAndStatusAndGivenAtAfterAndIsActiveTrue(
                any(), any(), any())).thenReturn(1L);

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.recordPrnDose(med.getId(), prnRequest()));
        assertTrue(ex.getMessage().contains("Minimum interval"));
    }

    @Test
    void prnDose_blockedAtDailyCap() {
        MedicationAdministration med = prnOrder();
        when(doseRepository.findFirstByMedicationIdAndStatusAndIsActiveTrueOrderByGivenAtDesc(
                med.getId(), DoseStatus.GIVEN)).thenReturn(Optional.empty());
        when(doseRepository.countByMedicationIdAndStatusAndGivenAtAfterAndIsActiveTrue(
                any(), any(), any())).thenReturn(4L); // cap = 4

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.recordPrnDose(med.getId(), prnRequest()));
        assertTrue(ex.getMessage().contains("24-hour cap"));
    }

    @Test
    void prnVitalsGate_failsClosed_whenNoVitalsOnRecord() {
        MedicationAdministration med = prnOrder();
        med.setGateParameter(VitalGateParameter.SYSTOLIC_BP);
        med.setGateComparator(VitalGateComparator.GTE);
        med.setGateThreshold(100.0);
        when(doseRepository.findFirstByMedicationIdAndStatusAndIsActiveTrueOrderByGivenAtDesc(
                med.getId(), DoseStatus.GIVEN)).thenReturn(Optional.empty());
        when(doseRepository.countByMedicationIdAndStatusAndGivenAtAfterAndIsActiveTrue(
                any(), any(), any())).thenReturn(0L);
        when(vitalSignsRepository.findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(
                visit.getId())).thenReturn(Optional.empty());

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.recordPrnDose(med.getId(), prnRequest()));
        assertTrue(ex.getMessage().contains("PRN dose blocked"));
    }

    @Test
    void prnVitalsGate_blocksWhenThresholdUnmet_andRecordsEvaluationOnOverride() {
        MedicationAdministration med = prnOrder();
        med.setGateParameter(VitalGateParameter.SYSTOLIC_BP);
        med.setGateComparator(VitalGateComparator.GTE);
        med.setGateThreshold(100.0);
        when(doseRepository.findFirstByMedicationIdAndStatusAndIsActiveTrueOrderByGivenAtDesc(
                med.getId(), DoseStatus.GIVEN)).thenReturn(Optional.empty());
        when(doseRepository.countByMedicationIdAndStatusAndGivenAtAfterAndIsActiveTrue(
                any(), any(), any())).thenReturn(0L);
        VitalSigns vitals = VitalSigns.builder()
                .visit(visit).recordedAt(Instant.now()).systolicBp(85).build();
        when(vitalSignsRepository.findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(
                visit.getId())).thenReturn(Optional.of(vitals));

        // Blocked without override (SBP 85 < 100).
        assertThrows(ClinicalBusinessException.class,
                () -> service.recordPrnDose(med.getId(), prnRequest()));

        // Override with justification records the dose + evaluation + alert.
        RecordPrnDoseRequest overrideReq = RecordPrnDoseRequest.builder()
                .prnReason("pain 8/10")
                .override(true)
                .overrideJustification("Consultant approved despite borderline BP")
                .build();
        var response = service.recordPrnDose(med.getId(), overrideReq);
        assertTrue(response.isOverride());
        assertNotNull(response.getGateEvaluation());
        assertTrue(response.getGateEvaluation().contains("FAILED"));
        verify(clinicalAlertRepository, atLeastOnce()).save(any(ClinicalAlert.class));
    }

    @Test
    void prnVitalsGate_passes_andRecordsEvaluation() {
        MedicationAdministration med = prnOrder();
        med.setGateParameter(VitalGateParameter.SYSTOLIC_BP);
        med.setGateComparator(VitalGateComparator.GTE);
        med.setGateThreshold(100.0);
        when(doseRepository.findFirstByMedicationIdAndStatusAndIsActiveTrueOrderByGivenAtDesc(
                med.getId(), DoseStatus.GIVEN)).thenReturn(Optional.empty());
        when(doseRepository.countByMedicationIdAndStatusAndGivenAtAfterAndIsActiveTrue(
                any(), any(), any())).thenReturn(0L);
        VitalSigns vitals = VitalSigns.builder()
                .visit(visit).recordedAt(Instant.now()).systolicBp(132).build();
        when(vitalSignsRepository.findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(
                visit.getId())).thenReturn(Optional.of(vitals));

        var response = service.recordPrnDose(med.getId(), prnRequest());

        assertEquals(DoseStatus.GIVEN, response.getStatus());
        assertNotNull(response.getGateEvaluation());
        assertTrue(response.getGateEvaluation().contains("passed"));
        assertEquals("pain 6/10", response.getPrnReason());
    }

    // ════════════════════════════════════════════════════════════════
    // High-alert approval gate
    // ════════════════════════════════════════════════════════════════

    @Test
    void approveOrder_blocksPrescriberSelfApproval() {
        User prescriber = user(Role.DOCTOR, Designation.MEDICAL_OFFICER);
        MedicationAdministration med = order(PrescriptionType.ONE_TIME);
        med.setStatus(MedicationStatus.PENDING_APPROVAL);
        med.setApprovalRequired(true);
        med.setPrescribedBy(prescriber);
        when(medicationRepository.findByIdAndIsActiveTrue(med.getId()))
                .thenReturn(Optional.of(med));
        authenticateAs(prescriber);

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.approveOrder(med.getId(), new ApproveOrderRequest()));
        assertTrue(ex.getMessage().contains("Separation of duties"));
        assertEquals(MedicationStatus.PENDING_APPROVAL, med.getStatus());
    }

    @Test
    void approveOrder_chargeNurse_activatesOrderAndSeedsFirstDose() {
        User prescriber = user(Role.DOCTOR, Designation.MEDICAL_OFFICER);
        User charge = user(Role.NURSE, Designation.CHARGE_NURSE);
        MedicationAdministration med = order(PrescriptionType.SCHEDULED);
        med.setStatus(MedicationStatus.PENDING_APPROVAL);
        med.setApprovalRequired(true);
        med.setPrescribedBy(prescriber);
        when(medicationRepository.findByIdAndIsActiveTrue(med.getId()))
                .thenReturn(Optional.of(med));
        authenticateAs(charge);

        service.approveOrder(med.getId(), new ApproveOrderRequest());

        assertEquals(MedicationStatus.PRESCRIBED, med.getStatus());
        assertNotNull(med.getApprovedAt());
        verify(medicationService).createInitialDoseIfNeeded(med, visit);
    }

    @Test
    void approveOrder_staffNurse_isRejected() {
        User staff = user(Role.NURSE, Designation.STAFF_NURSE);
        MedicationAdministration med = order(PrescriptionType.ONE_TIME);
        med.setStatus(MedicationStatus.PENDING_APPROVAL);
        when(medicationRepository.findByIdAndIsActiveTrue(med.getId()))
                .thenReturn(Optional.of(med));
        authenticateAs(staff);

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.approveOrder(med.getId(), new ApproveOrderRequest()));
        assertTrue(ex.getMessage().contains("charge nurse"));
    }

    // ════════════════════════════════════════════════════════════════
    // Pending approval is not administrable
    // ════════════════════════════════════════════════════════════════

    @Test
    void administerDose_blockedWhileOrderAwaitsApproval() {
        MedicationAdministration med = order(PrescriptionType.ONE_TIME);
        med.setStatus(MedicationStatus.PENDING_APPROVAL);
        MedicationDose dose = dueDose(med, Instant.now(), 1);

        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> service.administerDose(dose.getId(), new AdministerDoseRequest()));
        assertTrue(ex.getMessage().contains("awaiting charge-nurse approval"));
    }
}
