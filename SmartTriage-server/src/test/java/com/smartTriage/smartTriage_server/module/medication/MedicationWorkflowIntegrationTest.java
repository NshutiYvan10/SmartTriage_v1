package com.smartTriage.smartTriage_server.module.medication;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.AccountStatus;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.DoseStatus;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.enums.VitalGateComparator;
import com.smartTriage.smartTriage_server.common.enums.VitalGateParameter;
import com.smartTriage.smartTriage_server.common.enums.VitalSource;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.ApproveOrderRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.DiscontinueOrderRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.PrescribeMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.RecordPrnDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationDoseMonitorService;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationScheduleService;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationService;
import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
import com.smartTriage.smartTriage_server.module.medsafety.repository.DrugFormularyRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.dto.RecordVitalsRequest;
import com.smartTriage.smartTriage_server.module.vital.service.VitalSignsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end medication workflow against REAL PostgreSQL (Testcontainers,
 * Flyway from scratch): typed prescribe → high-alert approval gate →
 * dose administration with verification → schedule roll-forward → PRN
 * gates with real vitals rows → missed-dose escalation → discontinue →
 * handover audit text. Every test runs in a rolled-back transaction.
 */
@Transactional
class MedicationWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private DrugFormularyRepository formularyRepository;
    @Autowired private MedicationAdministrationRepository medicationRepository;
    @Autowired private MedicationDoseRepository doseRepository;
    @Autowired private ClinicalAlertRepository alertRepository;

    @Autowired private MedicationService medicationService;
    @Autowired private MedicationScheduleService scheduleService;
    @Autowired private MedicationDoseMonitorService doseMonitor;
    @Autowired private VitalSignsService vitalSignsService;

    private Hospital hospital;
    private Patient patient;
    private Visit visit;
    private User doctor;
    private User nurse;
    private User chargeNurse;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        hospital = new Hospital();
        hospital.setName("IT Hospital " + suffix);
        hospital.setHospitalCode("IT-" + suffix);
        hospital = hospitalRepository.save(hospital);

        doctor = seedUser("dr-" + suffix, Role.DOCTOR, Designation.MEDICAL_OFFICER);
        nurse = seedUser("rn-" + suffix, Role.NURSE, Designation.STAFF_NURSE);
        chargeNurse = seedUser("cn-" + suffix, Role.NURSE, Designation.CHARGE_NURSE);

        patient = new Patient();
        patient.setFirstName("Inte");
        patient.setLastName("Gration");
        patient.setHospital(hospital);
        patient = patientRepository.save(patient);

        visit = new Visit();
        visit.setPatient(patient);
        visit.setHospital(hospital);
        visit.setVisitNumber("IT-V-" + suffix);
        visit.setArrivalTime(Instant.now().minus(Duration.ofHours(2)));
        visit.setStatus(VisitStatus.UNDER_TREATMENT);
        visit.setCurrentEdZone(EdZone.GENERAL);
        visit = visitRepository.save(visit);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private User seedUser(String handle, Role role, Designation designation) {
        User u = new User();
        u.setFirstName(handle);
        u.setLastName("Test");
        u.setEmail(handle + "@it.test");
        u.setPasswordHash("not-a-real-hash");
        u.setRole(role);
        u.setDesignation(designation);
        u.setHospital(hospital);
        u.setAccountStatus(AccountStatus.ACTIVE);
        return userRepository.save(u);
    }

    private void actAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private PrescribeMedicationRequest.PrescribeMedicationRequestBuilder baseOrder(String drug) {
        return PrescribeMedicationRequest.builder()
                .visitId(visit.getId())
                .drugName(drug)
                .route(MedicationRoute.IV)
                .doseValue(new BigDecimal("1000"))
                .doseUnit("mg");
    }

    // ════════════════════════════════════════════════════════════════

    @Test
    void scheduledOrder_fullLifecycle_prescribeAdministerRollForwardComplete() {
        actAs(doctor);
        MedicationResponse order = medicationService.prescribe(baseOrder("Integrationcillin")
                .prescriptionType(PrescriptionType.SCHEDULED)
                .intervalHours(4.0)
                .maxDoses(2)
                .build());

        assertEquals(MedicationStatus.PRESCRIBED, order.getStatus());
        List<MedicationDose> doses = doseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId());
        assertEquals(1, doses.size());
        assertEquals(DoseStatus.DUE, doses.get(0).getStatus());

        // Nurse gives dose #1 (verified value matches the order).
        actAs(nurse);
        scheduleService.administerDose(doses.get(0).getId(), AdministerDoseRequest.builder()
                .doseValue(new BigDecimal("1000")).doseUnit("mg").build());

        doses = doseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId());
        assertEquals(2, doses.size());
        assertEquals(DoseStatus.GIVEN, doses.get(0).getStatus());
        assertEquals(DoseStatus.DUE, doses.get(1).getStatus());
        assertEquals(doses.get(0).getGivenAt().plus(Duration.ofHours(4)), doses.get(1).getDueAt());

        // Dose #2 completes the course (maxDoses = 2).
        scheduleService.administerDose(doses.get(1).getId(), new AdministerDoseRequest());
        MedicationAdministration completed =
                medicationRepository.findByIdAndIsActiveTrue(order.getId()).orElseThrow();
        assertEquals(MedicationStatus.COMPLETED, completed.getStatus());
        assertNotNull(completed.getCompletedAt());
    }

    @Test
    void doseVerification_rejectsTenfoldSlip_endToEnd() {
        actAs(doctor);
        MedicationResponse order = medicationService.prescribe(baseOrder("Integrationcillin")
                .prescriptionType(PrescriptionType.ONE_TIME)
                .build());
        MedicationDose dose = doseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId())
                .get(0);

        actAs(nurse);
        assertThrows(ClinicalBusinessException.class, () ->
                scheduleService.administerDose(dose.getId(), AdministerDoseRequest.builder()
                        .doseValue(new BigDecimal("100")).doseUnit("mg").build()));
    }

    @Test
    void highAlertOrder_approvalGate_endToEnd() {
        formularyRepository.save(DrugFormulary.builder()
                .genericName("Integrationtestium")
                .doseUnit("MG")
                .isHighAlert(true)
                .build());

        actAs(doctor);
        MedicationResponse order = medicationService.prescribe(baseOrder("Integrationtestium")
                .prescriptionType(PrescriptionType.ONE_TIME)
                .build());

        // Gated: PENDING_APPROVAL, no administrable dose, approval alert raised.
        assertEquals(MedicationStatus.PENDING_APPROVAL, order.getStatus());
        assertTrue(doseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId())
                .isEmpty());
        assertTrue(alertRepository
                .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                        visit.getId(), AlertType.MEDICATION_APPROVAL_REQUIRED));

        // The prescriber cannot approve their own order.
        assertThrows(ClinicalBusinessException.class,
                () -> scheduleService.approveOrder(order.getId(), new ApproveOrderRequest()));

        // The charge nurse can — order activates and dose #1 appears.
        actAs(chargeNurse);
        MedicationResponse approved =
                scheduleService.approveOrder(order.getId(), new ApproveOrderRequest());
        assertEquals(MedicationStatus.PRESCRIBED, approved.getStatus());
        assertEquals(1, doseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId())
                .size());
    }

    @Test
    void prnVitalsGate_failsClosed_thenPassesWithRealVitals() {
        actAs(doctor);
        MedicationResponse order = medicationService.prescribe(baseOrder("Integrationcillin")
                .prescriptionType(PrescriptionType.PRN)
                .prnIndication("pain")
                .prnMinIntervalHours(4.0)
                .gateParameter(VitalGateParameter.SYSTOLIC_BP)
                .gateComparator(VitalGateComparator.GTE)
                .gateThreshold(100.0)
                .build());

        // No vitals on record → fail-closed.
        actAs(nurse);
        RecordPrnDoseRequest give = RecordPrnDoseRequest.builder().prnReason("pain 7/10").build();
        ClinicalBusinessException blocked = assertThrows(ClinicalBusinessException.class,
                () -> scheduleService.recordPrnDose(order.getId(), give));
        assertTrue(blocked.getMessage().contains("PRN dose blocked"));

        // Record real vitals above the threshold → the gate passes.
        vitalSignsService.recordVitals(RecordVitalsRequest.builder()
                .visitId(visit.getId())
                .systolicBp(124)
                .heartRate(82)
                .source(VitalSource.MANUAL_ENTRY)
                .build());
        var dose = scheduleService.recordPrnDose(order.getId(), give);
        assertEquals(DoseStatus.GIVEN, dose.getStatus());
        assertTrue(dose.getGateEvaluation().contains("passed"));

        // Immediately again → minimum-interval guard blocks.
        ClinicalBusinessException interval = assertThrows(ClinicalBusinessException.class,
                () -> scheduleService.recordPrnDose(order.getId(), give));
        assertTrue(interval.getMessage().contains("Minimum interval"));
    }

    @Test
    void missedDose_escalates_andTheCourseSurvives() {
        actAs(doctor);
        MedicationResponse order = medicationService.prescribe(baseOrder("Integrationcillin")
                .prescriptionType(PrescriptionType.SCHEDULED)
                .intervalHours(8.0)
                .build());
        MedicationDose first = doseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId())
                .get(0);

        // Time-travel the dose 2 h past due (missed threshold is 60 min).
        Instant dueAt = Instant.now().minus(Duration.ofHours(2));
        first.setDueAt(dueAt);
        doseRepository.save(first);

        doseMonitor.tick();

        MedicationDose missed = doseRepository.findByIdAndIsActiveTrue(first.getId()).orElseThrow();
        assertEquals(DoseStatus.MISSED, missed.getStatus());
        assertTrue(alertRepository
                .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                        visit.getId(), AlertType.MEDICATION_DOSE_MISSED));
        // The schedule rolled forward from the missed slot.
        List<MedicationDose> doses = doseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId());
        assertEquals(2, doses.size());
        assertEquals(DoseStatus.DUE, doses.get(1).getStatus());
        assertEquals(dueAt.plus(Duration.ofHours(8)), doses.get(1).getDueAt());
    }

    @Test
    void discontinue_cancelsOpenDoses_andHandoverAuditTellsTheWholeStory() {
        actAs(doctor);
        MedicationResponse order = medicationService.prescribe(baseOrder("Integrationcillin")
                .prescriptionType(PrescriptionType.SCHEDULED)
                .intervalHours(6.0)
                .build());

        scheduleService.discontinueOrder(order.getId(), DiscontinueOrderRequest.builder()
                .reason("Culture results — switching to oral therapy")
                .build());

        MedicationAdministration stopped =
                medicationRepository.findByIdAndIsActiveTrue(order.getId()).orElseThrow();
        assertEquals(MedicationStatus.DISCONTINUED, stopped.getStatus());
        List<MedicationDose> doses = doseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId());
        assertEquals(1, doses.size());
        assertEquals(DoseStatus.CANCELLED, doses.get(0).getStatus());

        String audit = scheduleService.buildMedicationAuditText(visit);
        assertTrue(audit.contains("Integrationcillin"));
        assertTrue(audit.contains("Discontinued"));
        assertTrue(audit.contains("Culture results"));
    }

    @Test
    void serverSideAllergyBlock_firesAgainstRealAllergyRows() {
        // The patient's free-text allergies say penicillins — prescribing
        // amoxicillin without an acknowledged override must be rejected
        // by the SERVER (S1), regardless of any client-side dialog.
        patient.setKnownAllergies("penicillin");
        patientRepository.save(patient);

        actAs(doctor);
        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> medicationService.prescribe(baseOrder("Amoxicillin")
                        .prescriptionType(PrescriptionType.ONE_TIME)
                        .build()));
        assertTrue(ex.getMessage().contains("allergy safety check"));
    }
}
