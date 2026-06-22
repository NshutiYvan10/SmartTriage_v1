package com.smartTriage.smartTriage_server.journey;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.auth.dto.AuthResponse;
import com.smartTriage.smartTriage_server.module.auth.dto.LoginRequest;
import com.smartTriage.smartTriage_server.module.auth.service.AuthService;
import com.smartTriage.smartTriage_server.module.clinical.dto.CreateClinicalNoteRequest;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.clinical.service.ClinicalNoteService;
import com.smartTriage.smartTriage_server.module.documentation.dto.ClinicalDocumentResponse;
import com.smartTriage.smartTriage_server.module.documentation.service.ClinicalDocumentService;
import com.smartTriage.smartTriage_server.module.ems.dto.CreateEmsRunRequest;
import com.smartTriage.smartTriage_server.module.ems.dto.EmsRunResponse;
import com.smartTriage.smartTriage_server.module.ems.dto.FieldTriageRequest;
import com.smartTriage.smartTriage_server.module.ems.dto.PreregisterRequest;
import com.smartTriage.smartTriage_server.module.ems.service.EmsRunService;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
import com.smartTriage.smartTriage_server.module.handover.service.HandoverReportService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningRequest;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningResponse;
import com.smartTriage.smartTriage_server.module.isolation.service.InfectionIsolationService;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.service.HypoglycemiaService;
import com.smartTriage.smartTriage_server.module.lab.dto.AcknowledgeCriticalRequest;
import com.smartTriage.smartTriage_server.module.lab.dto.OrderLabRequest;
import com.smartTriage.smartTriage_server.module.lab.dto.RecordLabResultRequest;
import com.smartTriage.smartTriage_server.module.lab.dto.ReceiveSpecimenRequest;
import com.smartTriage.smartTriage_server.module.lab.dto.LabOrderResponse;
import com.smartTriage.smartTriage_server.module.lab.service.LabOrderService;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.PrescribeMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.RecordPrnDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationScheduleService;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationService;
import com.smartTriage.smartTriage_server.module.pathway.entity.ClinicalPathway;
import com.smartTriage.smartTriage_server.module.pathway.repository.ClinicalPathwayRepository;
import com.smartTriage.smartTriage_server.module.pathway.service.ClinicalPathwayService;
import com.smartTriage.smartTriage_server.module.sepsis.entity.SepsisScreening;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisScreeningRequest;
import com.smartTriage.smartTriage_server.module.sepsis.service.SepsisService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COMPREHENSIVE clinical workflow E2E against real PostgreSQL — the whole ED journey from
 * a paramedic field call to a signed discharge, across every major role and feature.
 *
 * Each step is executed and recorded PASS/FAIL independently (so one break doesn't hide the
 * rest); the per-step report is printed at the end and the test fails if any step FAILed.
 * Exercises the REAL service beans, state machine, alert pipeline, and persistence — it does
 * NOT click a UI or assert WebSocket delivery (those are noted PARTIAL in the human report).
 */
@Transactional
class FullClinicalWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthService authService;
    @Autowired private EmsRunService emsRunService;
    @Autowired private TriageService triageService;
    @Autowired private ClinicalNoteService clinicalNoteService;
    @Autowired private LabOrderService labOrderService;
    @Autowired private MedicationService medicationService;
    @Autowired private MedicationScheduleService medicationScheduleService;
    @Autowired private MedicationDoseRepository medicationDoseRepository;
    @Autowired private SepsisService sepsisService;
    @Autowired private HypoglycemiaService hypoglycemiaService;
    @Autowired private HypoglycemiaEventRepository hypoglycemiaEventRepository;
    @Autowired private InfectionIsolationService infectionIsolationService;
    @Autowired private ClinicalPathwayService clinicalPathwayService;
    @Autowired private ClinicalPathwayRepository clinicalPathwayRepository;
    @Autowired private ClinicalAlertRepository clinicalAlertRepository;
    @Autowired private ClinicalNoteRepository clinicalNoteRepository;
    @Autowired private HandoverReportService handoverReportService;
    @Autowired private ClinicalDocumentService clinicalDocumentService;
    @Autowired private VisitService visitService;
    @Autowired private VisitRepository visitRepository;

    private static final String PW = "password123";
    private final List<String> report = new ArrayList<>();

    // shared journey state
    private UUID hid, visitId, labOrderId, handoverId;
    private UUID oneTimeId, scheduledId, prnId, continuousId;
    private String paramedicE, registrarE, triageE, doctorE, nurseE, labTechE, incomingDocE;

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @FunctionalInterface interface Step { void run() throws Exception; }

    private void step(String name, Step body) {
        try { body.run(); report.add("PASS    | " + name); }
        catch (Throwable t) {
            String m = t.getMessage() != null ? t.getMessage() : t.toString();
            report.add("FAIL    | " + name + " | " + m.replaceAll("\\s+", " ").trim());
        }
    }
    private void partial(String name, String note) { report.add("PARTIAL | " + name + " | " + note); }

    @Test
    void fullWorkflow_paramedicToDischarge() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        Hospital h = hospitalRepository.save(Hospital.builder()
                .name("Full E2E Hospital " + s).hospitalCode("FE2-" + s).build());
        hid = h.getId();
        paramedicE = "para-" + s + "@t.rw"; registrarE = "reg-" + s + "@t.rw";
        triageE = "tri-" + s + "@t.rw"; doctorE = "doc-" + s + "@t.rw";
        nurseE = "nur-" + s + "@t.rw"; labTechE = "lab-" + s + "@t.rw";
        incomingDocE = "doc2-" + s + "@t.rw";
        createUser(paramedicE, Role.PARAMEDIC, Designation.PARAMEDIC);
        createUser(registrarE, Role.REGISTRAR, Designation.REGISTRAR);
        createUser(triageE, Role.NURSE, Designation.CHARGE_NURSE);
        createUser(doctorE, Role.DOCTOR, Designation.MEDICAL_OFFICER);
        createUser(nurseE, Role.NURSE, Designation.STAFF_NURSE);
        createUser(labTechE, Role.LAB_TECHNICIAN, Designation.LAB_TECHNICIAN);
        createUser(incomingDocE, Role.DOCTOR, Designation.MEDICAL_OFFICER);

        // ── 0. Every role logs in (real auth → JWT) ──
        step("0. Login — all 7 roles authenticate (real JWT)", () -> {
            for (String e : List.of(paramedicE, registrarE, triageE, doctorE, nurseE, labTechE, incomingDocE)) {
                AuthResponse a = authService.login(LoginRequest.builder().email(e).password(PW).build());
                assertNotNull(a.getAccessToken(), "no token for " + e);
            }
        });

        // ── 1. Paramedic: create run, field-triage RED, lights, destination, submit ──
        final UUID[] runId = new UUID[1];
        step("1. Paramedic — EMS field run + engine field-triage (RED) + lights + destination + pre-arrival submit", () -> {
            actingAs(paramedicE);
            EmsRunResponse run = emsRunService.createRun(CreateEmsRunRequest.builder()
                    .hospitalId(hid).service(EmsService.SAMU).unitCallsign("SAMU-7")
                    .paramedicName("Medic Ishimwe").patientAgeYears(54).patientSex("M")
                    .incidentLocation("KN 5 Rd").mechanism("RTA — motorcycle vs car, ejected")
                    .historySummary("Unresponsive at scene").build());
            runId[0] = run.getId();
            EmsRunResponse triaged = emsRunService.computeFieldTriage(runId[0], FieldTriageRequest.builder()
                    .respiratoryRate(28).heartRate(132).systolicBp(86).spo2(88).temperature(36.1).gcs(9)
                    .mobility(MobilityStatus.STRETCHER).avpu(AvpuScore.PAIN).traumaStatus(TraumaStatus.TRAUMA)
                    .hasAirwayCompromise(true).reason("Airway compromise post-RTA").build());
            assertEquals("RED", triaged.getFieldTriageCategory(), "field triage must compute RED");
            EmsRunResponse lit = emsRunService.setLights(runId[0], true);
            assertTrue(lit.isLightsActive(), "lights must be active");
            EmsRunResponse pre = emsRunService.preregister(runId[0],
                    PreregisterRequest.builder().etaMinutes(8).preArrivalNote("Critical RTA inbound").build());
            assertNotNull(pre.getVisitId(), "pre-registration must create/link a visit");
            visitId = pre.getVisitId();
        });

        // ── 2. Registrar: receive pre-arrival alert + temp-identifier patient record ──
        step("2. Registrar — pre-arrival alert routed + temp-identifier (placeholder) patient record created", () -> {
            assertTrue(clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(visitId, AlertType.EMS_PRE_ARRIVAL),
                    "an EMS_PRE_ARRIVAL alert must exist for the receiving ED/registrar");
            Visit v = reload(visitId);
            assertNotNull(v.getPatient(), "a (placeholder) patient record must exist");
            assertTrue(v.getPatient().isUnidentified(), "unknown RED arrival must use a temporary identifier");
            assertNotNull(v.getPatient().getPlaceholderLabel(), "temp identifier (placeholder label) must be assigned");
        });

        // ── 3. Confirm arrival + triage nurse assigns category + zone ──
        step("3. Triage nurse — confirm arrival (AWAITING_TRIAGE) → triage assigns category + zone", () -> {
            actingAs(paramedicE);
            emsRunService.confirmArrival(runId[0]);
            assertEquals(VisitStatus.AWAITING_TRIAGE, reload(visitId).getStatus(), "arrival → AWAITING_TRIAGE");
            actingAs(triageE);
            triageService.performTriage(PerformTriageRequest.builder()
                    .visitId(visitId).mobility(MobilityStatus.STRETCHER).avpu(AvpuScore.PAIN)
                    .traumaStatus(TraumaStatus.TRAUMA).respiratoryRate(28).heartRate(130).systolicBP(88).spo2(89)
                    .hasAirwayCompromise(true).build());
            Visit v = reload(visitId);
            assertNotNull(v.getCurrentTriageCategory(), "triage must assign a category");
            assertEquals(VisitStatus.TRIAGED, v.getStatus(), "status TRIAGED after triage");
            assertNotNull(v.getCurrentEdZone(), "triage must route a zone");
        });

        // ── 4a. Doctor writes a clinical note ──
        step("4a. Doctor — clinical note (authored by principal)", () -> {
            actingAs(doctorE);
            var n = clinicalNoteService.createNote(CreateClinicalNoteRequest.builder()
                    .visitId(visitId).noteType(NoteType.DOCTOR_NOTE)
                    .content("Polytrauma, GCS 9, airway at risk. Plan: RSI, FAST, bloods, CT.").build());
            assertNotNull(n.getId());
        });

        // ── 4b. Doctor orders labs (STAT potassium) ──
        step("4b. Doctor — order STAT lab", () -> {
            actingAs(doctorE);
            LabOrderResponse lab = labOrderService.orderLab(visitId, OrderLabRequest.builder()
                    .visitId(visitId).testName("Serum Potassium").priority(LabPriority.STAT)
                    .clinicalIndication("Crush injury — hyperkalaemia risk").build());
            labOrderId = lab.getId();
            assertNotNull(labOrderId);
        });

        // ── 4c. Doctor prescribes one of each medication type ──
        step("4c. Doctor — prescribe ONE_TIME med", () -> {
            actingAs(doctorE);
            oneTimeId = medicationService.prescribe(PrescribeMedicationRequest.builder()
                    .visitId(visitId).drugName("Paracetamol").dose("1 g").doseValue(new BigDecimal("1")).doseUnit("g")
                    .route(MedicationRoute.PO).frequency("ONCE").prescriptionType(PrescriptionType.ONE_TIME).build()).getId();
            assertNotNull(oneTimeId);
        });
        step("4c. Doctor — prescribe SCHEDULED med", () -> {
            actingAs(doctorE);
            scheduledId = medicationService.prescribe(PrescribeMedicationRequest.builder()
                    .visitId(visitId).drugName("Ceftriaxone").dose("1 g").doseValue(new BigDecimal("1")).doseUnit("g")
                    .route(MedicationRoute.IV).frequency("Q24H").prescriptionType(PrescriptionType.SCHEDULED)
                    .startAt(Instant.now()).intervalHours(24.0).build()).getId();
            assertNotNull(scheduledId);
        });
        step("4c. Doctor — prescribe PRN med", () -> {
            actingAs(doctorE);
            prnId = medicationService.prescribe(PrescribeMedicationRequest.builder()
                    .visitId(visitId).drugName("Metoclopramide").dose("10 mg").doseValue(new BigDecimal("10")).doseUnit("mg")
                    .route(MedicationRoute.IV).prescriptionType(PrescriptionType.PRN)
                    .prnIndication("nausea").prnMinIntervalHours(6.0).prnMaxDosesPerDay(3).build()).getId();
            assertNotNull(prnId);
        });
        step("4c. Doctor — prescribe CONTINUOUS infusion", () -> {
            actingAs(doctorE);
            continuousId = medicationService.prescribe(PrescribeMedicationRequest.builder()
                    .visitId(visitId).drugName("Sodium Chloride 0.9%").dose("1 L").doseValue(new BigDecimal("1")).doseUnit("L")
                    .route(MedicationRoute.IV).prescriptionType(PrescriptionType.CONTINUOUS)
                    .rateValue(250.0).rateUnit("mL/hr").build()).getId();
            assertNotNull(continuousId);
        });

        // ── 4d. Doctor runs sepsis screening ──
        step("4d. Doctor — sepsis screening", () -> {
            actingAs(doctorE);
            SepsisScreening sc = sepsisService.screenPatient(visitId, SepsisScreeningRequest.builder()
                    .suspectedInfectionSource("chest").lactateLevel(4.5).wbcCount(18.0).wbcBandsElevated(true)
                    .notes("Febrile, tachycardic").build());
            assertNotNull(sc.getId());
            assertNotNull(sc.getSepsisStatus(), "sepsis status must be computed");
        });

        // ── 4e. Doctor / monitoring runs hypoglycemia detection ──
        step("4e. Hypoglycemia detection (glucose 2.0 mmol/L)", () -> {
            actingAs(doctorE);
            long before = hypoglycemiaEventRepository.count();
            hypoglycemiaService.evaluateGlucoseReading(reload(visitId), 2.0, false, "MANUAL_VITALS");
            assertTrue(hypoglycemiaEventRepository.count() > before, "a hypoglycemia event must be created");
        });

        // ── 4f. Doctor runs isolation screening ──
        step("4f. Doctor — isolation/infection screening", () -> {
            actingAs(doctorE);
            InfectionScreeningResponse iso = infectionIsolationService.screenPatient(visitId,
                    InfectionScreeningRequest.builder().screenedByName("Dr X")
                            .hasFever(true).hasCough(true).hasCoughDurationWeeks(3).hasRecentTravel(true)
                            .recentTravelLocation("DRC border").notes("? TB ? VHF").build());
            assertNotNull(iso, "isolation screening must return a result");
        });

        // ── 4g. Doctor activates a clinical pathway ──
        step("4g. Doctor — activate clinical pathway", () -> {
            actingAs(doctorE);
            List<ClinicalPathway> pathways = clinicalPathwayRepository.findAllByIsActiveTrueOrderByPathwayNameAsc();
            assertFalse(pathways.isEmpty(), "seeded pathway definitions must exist");
            var act = clinicalPathwayService.activatePathway(visitId, pathways.get(0).getId(), "Dr X", "Polytrauma pathway");
            assertNotNull(act, "pathway activation must return");
        });

        // ── 5. Nurse administers the medications ──
        step("5. Nurse — administer ONE_TIME dose (separation of duties: nurse != prescriber)", () -> {
            actingAs(nurseE);
            administerFirstDose(oneTimeId);
        });
        step("5. Nurse — administer SCHEDULED dose", () -> {
            actingAs(nurseE);
            administerFirstDose(scheduledId);
        });
        step("5. Nurse — record PRN dose", () -> {
            actingAs(nurseE);
            medicationScheduleService.recordPrnDose(prnId, RecordPrnDoseRequest.builder()
                    .prnReason("nausea").notes("given").build());
        });
        partial("5. Nurse — CONTINUOUS infusion", "prescribed OK; a continuous infusion is rate-managed (started/titrated), not dose-by-dose administered — no discrete DUE dose to 'give'");

        // ── 6. Lab tech processes the order + enters a CRITICAL result ──
        step("6. Lab tech — collect → receive → process → record CRITICAL result (K 6.8) → doctor alerted", () -> {
            actingAs(labTechE);
            labOrderService.collectSpecimen(labOrderId, "Tech Niyrandom");
            labOrderService.receiveInLab(labOrderId, ReceiveSpecimenRequest.builder()
                    .accessionNumber("ACC-" + s).receivedByName("Tech Niyrandom").build());
            labOrderService.startProcessing(labOrderId, "Tech Niyrandom");
            LabOrderResponse res = labOrderService.recordResult(labOrderId, RecordLabResultRequest.builder()
                    .resultValue("6.8").resultUnit("mmol/L").resultNumeric(6.8).enteredByName("Tech Niyrandom").build());
            assertTrue(res.isCritical(), "K 6.8 mmol/L must be flagged CRITICAL");
            assertTrue(clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(visitId, AlertType.CRITICAL_LAB_RESULT),
                    "a CRITICAL_LAB_RESULT alert must be raised to the doctor");
        });

        // ── 7. Doctor acknowledges critical + updates notes + handover ──
        step("7a. Doctor — acknowledge critical lab (JCI read-back) → unack alert cleared", () -> {
            actingAs(doctorE);
            labOrderService.acknowledgeCriticalValue(labOrderId, AcknowledgeCriticalRequest.builder()
                    .acknowledgedByName("Dr X").readbackText("Potassium 6.8, will treat hyperkalaemia")
                    .contactMethod(CriticalContactMethod.PHONE).build());
            assertFalse(clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                    visitId, AlertType.CRITICAL_LAB_RESULT), "critical lab alert must no longer be unacknowledged");
        });
        step("7b. Doctor — progress note + assessment/plan note", () -> {
            actingAs(doctorE);
            clinicalNoteService.createNote(CreateClinicalNoteRequest.builder().visitId(visitId)
                    .noteType(NoteType.PROGRESS_NOTE).content("Hyperkalaemia treated: calcium, insulin-dextrose, salbutamol.").build());
            clinicalNoteService.createNote(CreateClinicalNoteRequest.builder().visitId(visitId)
                    .noteType(NoteType.TREATMENT_PLAN).content("Admit ICU vs discharge per response; monitor K.").build());
        });
        step("7c. Doctor — generate SBAR handover", () -> {
            actingAs(doctorE);
            HandoverReport hr = handoverReportService.generateReport(visitId, HandoverReportType.SHIFT_HANDOVER, "Dr X", null);
            handoverId = hr.getId();
            assertNotNull(handoverId);
            assertNotNull(hr.getMedicationAudit(), "handover must include the medication audit");
            assertNotNull(hr.getPatientSummary());
        });

        // ── 8. Incoming shift doctor receives handover + audit trail visible ──
        step("8. Incoming doctor — acknowledge handover + clinical/domain audit trail complete", () -> {
            actingAs(incomingDocE);
            HandoverReport ack = handoverReportService.acknowledgeHandover(handoverId, "Dr Incoming");
            assertTrue(ack.isAcknowledged(), "handover must be acknowledged by the incoming doctor");
            // Domain audit trail: notes (append-only), administered doses (actor+time), triage record.
            long notes = clinicalNoteRepository.findByVisitIdAndIsActiveTrueOrderByRecordedAtAsc(visitId).size();
            assertTrue(notes >= 3, "≥3 clinical notes must be on the record (got " + notes + ")");
            long givenDoses = medicationDoseRepository.findByVisitIdAndIsActiveTrueOrderByCreatedAtAsc(visitId)
                    .stream().filter(d -> d.getGivenAt() != null).count();
            assertTrue(givenDoses >= 2, "administered doses must be auditable (got " + givenDoses + ")");
        });

        // ── 9. Signed discharge summary + disposition ──
        step("9. Discharge — signed discharge summary + DISCHARGED_HOME disposition", () -> {
            actingAs(doctorE);
            ClinicalDocumentResponse ds = clinicalDocumentService.generateDischargeSummary(visitId);
            ClinicalDocumentResponse signed = clinicalDocumentService.signDocument(ds.getId());
            assertTrue(signed.isSigned(), "discharge summary must be signed");
            visitService.recordDisposition(visitId, DispositionRequest.builder()
                    .dispositionType(DispositionType.DISCHARGED_HOME)
                    .notes("Hyperkalaemia corrected; stable for discharge with safety-netting.").build());
            Visit v = reload(visitId);
            assertEquals(VisitStatus.DISCHARGED, v.getStatus(), "patient must end DISCHARGED");
            assertEquals(DispositionType.DISCHARGED_HOME, v.getDispositionType());
        });

        // ── Report ──
        System.out.println("\n================ FULL CLINICAL WORKFLOW E2E — STEP REPORT ================");
        report.forEach(System.out::println);
        long fails = report.stream().filter(r -> r.startsWith("FAIL")).count();
        long partials = report.stream().filter(r -> r.startsWith("PARTIAL")).count();
        System.out.println("================ " + report.size() + " steps · "
                + (report.size() - fails - partials) + " PASS · " + partials + " PARTIAL · " + fails + " FAIL ================\n");
        assertEquals(0, fails, "Some workflow steps FAILed — see the step report above.");
    }

    // ── helpers ──
    private void createUser(String email, Role role, Designation designation) {
        userService.createUser(CreateUserRequest.builder()
                .firstName("T").lastName(role.name()).email(email).password(PW)
                .role(role).designation(designation).hospitalId(hid).build());
    }
    private void actingAs(String email) {
        User u = userRepository.findByEmailAndIsActiveTrue(email).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }
    private Visit reload(UUID id) { return visitRepository.findByIdAndIsActiveTrue(id).orElseThrow(); }
    private void administerFirstDose(UUID orderId) {
        List<MedicationDose> doses = medicationDoseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(orderId);
        assertFalse(doses.isEmpty(), "order must have a dose to administer");
        medicationScheduleService.administerDose(doses.get(0).getId(),
                AdministerDoseRequest.builder().notes("given as prescribed").build());
        MedicationDose d = medicationDoseRepository.findByIdAndIsActiveTrue(doses.get(0).getId()).orElseThrow();
        assertNotNull(d.getGivenAt(), "dose must record givenAt");
    }
}
