package com.smartTriage.smartTriage_server.journey;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.DispositionType;
import com.smartTriage.smartTriage_server.common.enums.HandoverReportType;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MobilityStatus;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.TraumaStatus;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.auth.dto.AuthResponse;
import com.smartTriage.smartTriage_server.module.auth.dto.LoginRequest;
import com.smartTriage.smartTriage_server.module.auth.service.AuthService;
import com.smartTriage.smartTriage_server.module.documentation.entity.ClinicalDocument;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
import com.smartTriage.smartTriage_server.module.handover.service.HandoverReportService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.PrescribeMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationResponse;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationScheduleService;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationService;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientResponse;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import com.smartTriage.smartTriage_server.module.triage.dto.PerformTriageRequest;
import com.smartTriage.smartTriage_server.module.triage.service.TriageService;
import com.smartTriage.smartTriage_server.module.user.dto.CreateUserRequest;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.user.service.UserService;
import com.smartTriage.smartTriage_server.module.visit.dto.DispositionRequest;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import com.smartTriage.smartTriage_server.module.vital.dto.RecordVitalsRequest;
import com.smartTriage.smartTriage_server.module.vital.service.VitalSignsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full clinical patient-journey end-to-end test against REAL PostgreSQL (Testcontainers).
 *
 * Walks the whole ED flow the way the roles actually use it — log in as each role, then:
 *   Registrar  → register patient + open visit
 *   Triage nurse → triage (category assigned, status TRIAGED)
 *   Nurse      → record vital signs (monitoring)
 *   Doctor     → prescribe a medication
 *   Nurse      → administer the dose (separation of duties: administerer != prescriber)
 *   Doctor     → generate the SBAR handover, then discharge the patient home
 *
 * Each step authenticates the acting role and asserts the resulting state transition,
 * so a green run proves the cross-module journey + auth + persistence work together.
 *
 * Skipped automatically when Docker is absent (see {@link AbstractIntegrationTest}); runs
 * in CI against real Postgres. @Transactional → the whole journey rolls back per test.
 */
@Transactional
class PatientJourneyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthService authService;
    @Autowired private PatientService patientService;
    @Autowired private TriageService triageService;
    @Autowired private VitalSignsService vitalSignsService;
    @Autowired private MedicationService medicationService;
    @Autowired private MedicationScheduleService medicationScheduleService;
    @Autowired private MedicationDoseRepository medicationDoseRepository;
    @Autowired private HandoverReportService handoverReportService;
    @Autowired private ClinicalDocumentRepository clinicalDocumentRepository;
    @Autowired private VisitService visitService;
    @Autowired private VisitRepository visitRepository;

    private static final String PW = "password123";

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void fullJourney_registerToDischarge_acrossRoles() {
        String s = UUID.randomUUID().toString().substring(0, 8);

        // ── Setup: hospital + one user per role ──
        Hospital hospital = hospitalRepository.save(Hospital.builder()
                .name("Journey Hospital " + s).hospitalCode("JNY-" + s).build());
        UUID hid = hospital.getId();

        String registrarEmail = "registrar-" + s + "@test.rw";
        String triageEmail = "triage-" + s + "@test.rw";
        String doctorEmail = "doctor-" + s + "@test.rw";
        String nurseEmail = "nurse-" + s + "@test.rw";
        createUser(registrarEmail, Role.REGISTRAR, Designation.REGISTRAR, hid);
        createUser(triageEmail, Role.NURSE, Designation.CHARGE_NURSE, hid);
        createUser(doctorEmail, Role.DOCTOR, Designation.MEDICAL_OFFICER, hid);
        createUser(nurseEmail, Role.NURSE, Designation.STAFF_NURSE, hid);

        // ── Step 0: every role can LOG IN (real auth → JWT) ──
        for (String email : List.of(registrarEmail, triageEmail, doctorEmail, nurseEmail)) {
            AuthResponse auth = authService.login(
                    LoginRequest.builder().email(email).password(PW).build());
            assertNotNull(auth.getAccessToken(), "login must issue an access token for " + email);
            assertNotNull(auth.getRefreshToken(), "login must issue a refresh token for " + email);
        }

        // ── Step 1: Registrar registers a patient + opens a visit ──
        actingAs(registrarEmail);
        RegisterPatientResponse reg = patientService.registerPatientWithVisit(
                RegisterPatientRequest.builder()
                        .firstName("Jean").lastName("Mutoni-" + s)
                        .chiefComplaint("Fever and cough")
                        .hospitalId(hid)
                        .build());
        UUID visitId = reg.getVisit().getId();
        assertNotNull(visitId, "visit must be created");
        assertEquals(VisitStatus.REGISTERED, reload(visitId).getStatus(), "new visit is REGISTERED");

        // ── Step 2: Triage nurse triages → category assigned, status TRIAGED ──
        actingAs(triageEmail);
        triageService.performTriage(PerformTriageRequest.builder()
                .visitId(visitId)
                .mobility(MobilityStatus.WALKING)
                .avpu(AvpuScore.ALERT)
                .traumaStatus(TraumaStatus.NO_TRAUMA)
                .respiratoryRate(20).heartRate(96).systolicBP(122).temperature(38.4)
                .build());
        Visit triaged = reload(visitId);
        assertNotNull(triaged.getCurrentTriageCategory(), "triage must assign a category");
        assertEquals(VisitStatus.TRIAGED, triaged.getStatus(), "status is TRIAGED after triage");
        assertNotNull(triaged.getTriageTime(), "triageTime must be set");

        // ── Step 3: Nurse records vital signs (monitoring) ──
        actingAs(nurseEmail);
        vitalSignsService.recordVitals(RecordVitalsRequest.builder()
                .visitId(visitId)
                .heartRate(98).respiratoryRate(20).systolicBp(120).diastolicBp(78)
                .temperature(38.2).spo2(97)
                .build());

        // ── Step 4: Doctor prescribes a medication ──
        actingAs(doctorEmail);
        MedicationResponse order = medicationService.prescribe(PrescribeMedicationRequest.builder()
                .visitId(visitId)
                .drugName("Paracetamol")
                .dose("1 g").doseValue(new BigDecimal("1")).doseUnit("g")
                .route(MedicationRoute.PO)
                .frequency("ONCE")
                .prescriptionType(PrescriptionType.ONE_TIME)
                .build());
        assertNotNull(order.getId(), "prescription must persist an order");

        // ── Step 5: Nurse administers the dose (separation of duties: nurse != prescriber) ──
        actingAs(nurseEmail);
        List<MedicationDose> doses = medicationDoseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId());
        assertFalse(doses.isEmpty(), "a typed order must create at least one dose");
        UUID doseId = doses.get(0).getId();
        medicationScheduleService.administerDose(doseId,
                AdministerDoseRequest.builder().notes("Given as prescribed").build());
        MedicationDose given = medicationDoseRepository.findByIdAndIsActiveTrue(doseId).orElseThrow();
        assertNotNull(given.getGivenAt(), "administered dose must record givenAt");
        assertNotNull(given.getGivenBy(), "administered dose must record the administering clinician");

        // ── Step 6: Doctor generates the SBAR handover ──
        actingAs(doctorEmail);
        HandoverReport handover = handoverReportService.generateReport(
                visitId, HandoverReportType.SHIFT_HANDOVER, "Dr Journey", null);
        assertTrue(handover.getPatientSummary().contains("Mutoni-" + s),
                "handover patient summary must name the patient");
        assertNotNull(handover.getMedicationAudit(), "handover must carry the medication audit");

        // ── Step 7: Doctor discharges the patient home ──
        // DISCHARGED_HOME is guarded: a discharge-summary document must exist first.
        Visit visit = reload(visitId);
        clinicalDocumentRepository.save(ClinicalDocument.builder()
                .visit(visit)
                .documentType(ClinicalDocumentType.DISCHARGE_SUMMARY)
                .title("Discharge Summary")
                .content("Febrile illness, treated with paracetamol. Stable for discharge home.")
                .authorName("Dr Journey")
                .authorRole("DOCTOR")
                .isSigned(false)
                .isAmendment(false)
                .build());

        visitService.recordDisposition(visitId, DispositionRequest.builder()
                .dispositionType(DispositionType.DISCHARGED_HOME)
                .notes("Symptomatic improvement; safety-netting advice given.")
                .build());

        Visit discharged = reload(visitId);
        assertEquals(VisitStatus.DISCHARGED, discharged.getStatus(), "patient must end DISCHARGED");
        assertEquals(DispositionType.DISCHARGED_HOME, discharged.getDispositionType());
        assertNotNull(discharged.getDispositionTime(), "disposition time must be recorded");
    }

    // ── helpers ──

    private void createUser(String email, Role role, Designation designation, UUID hospitalId) {
        userService.createUser(CreateUserRequest.builder()
                .firstName("T").lastName(role.name())
                .email(email).password(PW)
                .role(role).designation(designation)
                .hospitalId(hospitalId)
                .build());
    }

    private void actingAs(String email) {
        User u = userRepository.findByEmailAndIsActiveTrue(email).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    private Visit reload(UUID visitId) {
        return visitRepository.findByIdAndIsActiveTrue(visitId).orElseThrow();
    }
}
