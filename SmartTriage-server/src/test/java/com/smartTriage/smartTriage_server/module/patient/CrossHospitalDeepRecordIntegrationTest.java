package com.smartTriage.smartTriage_server.module.patient;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.enums.ConsentGrantor;
import com.smartTriage.smartTriage_server.common.enums.DiagnosisType;
import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import com.smartTriage.smartTriage_server.common.enums.MedicationProductType;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.audit.repository.AuditLogRepository;
import com.smartTriage.smartTriage_server.module.clinical.entity.Diagnosis;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.DiagnosisRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.consent.dto.RecordDataSharingConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.dto.WithdrawConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.dto.DataSharingConsentResponse;
import com.smartTriage.smartTriage_server.common.enums.DataSharingConsentStatus;
import com.smartTriage.smartTriage_server.module.consent.entity.BreakTheGlassEvent;
import com.smartTriage.smartTriage_server.module.consent.repository.BreakTheGlassEventRepository;
import com.smartTriage.smartTriage_server.module.consent.service.DataSharingConsentService;
import com.smartTriage.smartTriage_server.module.documentation.entity.ClinicalDocument;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalDeepRecordResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.service.CrossHospitalDeepRecordService;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against REAL PostgreSQL for Phase 2: the cross-hospital DEEP-record read is gated by
 * the patient's data-sharing CONSENT, with an emergency BREAK-THE-GLASS override that is recorded
 * forensically. Fixture: same national ID at hospital A + B (shared identity); A's visit carries a
 * diagnosis, discharge summary, critical lab, doctor note, and an administered medication. Scenarios:
 * (1) consent GRANTED → served, basis CONSENT, provenance-tagged, no break-glass event;
 * (2) no consent → DENIED, no clinical data, consentRequired, audited;
 * (3) break-glass reason → served, basis BREAK_THE_GLASS, a forensic event persisted (prior=NONE);
 * (4) consent withdrawn → DENIED again;
 * (5) break-glass reason WHILE consent present → CONSENT wins, no event.
 */
@Transactional
class CrossHospitalDeepRecordIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private PatientService patientService;
    @Autowired private PatientRepository patientRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private DiagnosisRepository diagnosisRepository;
    @Autowired private InvestigationRepository investigationRepository;
    @Autowired private ClinicalNoteRepository clinicalNoteRepository;
    @Autowired private ClinicalDocumentRepository clinicalDocumentRepository;
    @Autowired private MedicationAdministrationRepository medicationAdministrationRepository;
    @Autowired private DataSharingConsentService dataSharingConsentService;
    @Autowired private CrossHospitalDeepRecordService deepRecordService;
    @Autowired private BreakTheGlassEventRepository breakTheGlassEventRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    // ── 1. consent GRANTED → deep history served at the other hospital ──
    @Test
    void withConsent_deepHistoryIsServedCrossHospital_provenanceTagged_noBreakGlass() {
        Seed seed = seedLinkedPatientWithClinicalData();

        authenticateAs(Role.NURSE, "Reception", "Clerk");
        grant(seed.nid);

        authenticateAs(Role.DOCTOR, "Grace", "Habimana");
        CrossHospitalDeepRecordResponse res = deepRecordService.getByNationalId(seed.nid, null);

        assertTrue(res.isFound());
        assertTrue(res.isAccessGranted(), "consent grants access");
        assertEquals("CONSENT", res.getAccessBasis());
        assertFalse(res.isConsentRequired());
        assertEquals(2, res.getLinkedHospitalCount(), "history spans both hospitals");
        assertNotNull(res.getHospitals());
        assertFalse(res.getHospitals().isEmpty());

        // The clinical detail recorded at hospital A must surface (provenance-tagged), bounded summary form.
        String all = res.getHospitals().toString();
        assertTrue(all.contains("Sepsis"), "diagnosis must appear");
        assertTrue(all.contains("[CRITICAL]"), "critical lab must appear");
        assertTrue(all.contains("Discharge"), "discharge summary must appear");
        assertTrue(all.contains("DOCTOR_NOTE"), "doctor note must appear");
        assertTrue(res.getMedicationHistory().stream().anyMatch(m -> m.contains("Ceftriaxone")),
                "cross-visit medication history must appear");
        assertTrue(res.getHospitals().stream().anyMatch(h -> h.getSourceHospital().contains("XH")),
                "each section is tagged with its source hospital");

        // No break-the-glass event when consent is the basis.
        assertTrue(breakTheGlassEventRepository
                        .findByPersonIdentityIdAndIsActiveTrueOrderByAccessedAtDesc(seed.identityId).isEmpty(),
                "consent path must NOT record a break-the-glass event");
        assertAudited("basis=CONSENT");
    }

    // ── 2. no consent → DENIED, no clinical data ──
    @Test
    void withoutConsentOrBreakGlass_accessIsDenied_withNoClinicalData() {
        Seed seed = seedLinkedPatientWithClinicalData();

        authenticateAs(Role.DOCTOR, "Grace", "Habimana");
        CrossHospitalDeepRecordResponse res = deepRecordService.getByNationalId(seed.nid, null);

        assertTrue(res.isFound(), "the person exists cross-hospital");
        assertFalse(res.isAccessGranted(), "no consent, no break-glass → denied");
        assertEquals("DENIED", res.getAccessBasis());
        assertTrue(res.isConsentRequired());
        assertNull(res.getHospitals(), "denied access must carry NO clinical sections");
        assertNull(res.getMedicationHistory(), "denied access must carry NO medication history");
        assertAudited("basis=DENIED");
    }

    // ── 3. break-the-glass → served + forensic event recorded ──
    @Test
    void breakTheGlass_servesRecord_andPersistsForensicEvent() {
        Seed seed = seedLinkedPatientWithClinicalData();

        authenticateAs(Role.DOCTOR, "Emergency", "Physician");
        CrossHospitalDeepRecordResponse res = deepRecordService.getByNationalId(
                seed.nid, "Unconscious trauma, no consent obtainable, history needed for safe care");

        assertTrue(res.isAccessGranted(), "break-the-glass grants emergency access");
        assertEquals("BREAK_THE_GLASS", res.getAccessBasis());
        assertNotNull(res.getHospitals());
        assertFalse(res.getHospitals().isEmpty());

        List<BreakTheGlassEvent> events = breakTheGlassEventRepository
                .findByPersonIdentityIdAndIsActiveTrueOrderByAccessedAtDesc(seed.identityId);
        assertEquals(1, events.size(), "exactly one forensic break-the-glass event");
        BreakTheGlassEvent e = events.get(0);
        assertTrue(e.getReason().contains("Unconscious trauma"), "the mandatory reason is recorded");
        assertEquals("NONE", e.getPriorConsentState(), "no prior consent existed");
        assertEquals("Emergency Physician", e.getActorName());
        assertEquals("DOCTOR", e.getActorRole());
        assertNotNull(e.getAccessedAt());
        assertAudited("basis=BREAK_THE_GLASS");
    }

    // ── 4. consent withdrawn → DENIED again ──
    @Test
    void withdrawnConsent_isNoLongerEffective_accessDenied() {
        Seed seed = seedLinkedPatientWithClinicalData();

        authenticateAs(Role.DOCTOR, "Grace", "Habimana");
        DataSharingConsentResponse granted = grant(seed.nid);
        // Withdraw it.
        dataSharingConsentService.withdrawConsent(granted.getId(),
                WithdrawConsentRequest.builder().reason("Patient revoked sharing").build());

        CrossHospitalDeepRecordResponse res = deepRecordService.getByNationalId(seed.nid, null);
        assertFalse(res.isAccessGranted(), "a withdrawn consent must not grant access");
        assertEquals("DENIED", res.getAccessBasis());
        assertNull(res.getHospitals());
    }

    // ── 5. break-glass reason WHILE consent present → CONSENT wins, no event ──
    @Test
    void consentPresent_breakGlassReasonIgnored_basisIsConsent_noEvent() {
        Seed seed = seedLinkedPatientWithClinicalData();

        authenticateAs(Role.DOCTOR, "Grace", "Habimana");
        grant(seed.nid);

        CrossHospitalDeepRecordResponse res = deepRecordService.getByNationalId(
                seed.nid, "would-be break glass reason");
        assertTrue(res.isAccessGranted());
        assertEquals("CONSENT", res.getAccessBasis(), "consent wins over a supplied break-glass reason");
        assertTrue(breakTheGlassEventRepository
                        .findByPersonIdentityIdAndIsActiveTrueOrderByAccessedAtDesc(seed.identityId).isEmpty(),
                "no forensic event when consent already covers the access");
    }

    // ────────────────────────── fixtures ──────────────────────────

    private record Seed(String nid, UUID identityId, UUID patientAId, UUID visitAId) {}

    private Seed seedLinkedPatientWithClinicalData() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String nid = "11991" + s;
        Hospital a = hospital("A-" + s);
        Hospital b = hospital("B-" + s);

        var regA = patientService.registerPatientWithVisit(reg(a.getId(), "Jean", "Niyonzima", nid));
        UUID patientAId = regA.getPatient().getId();
        UUID visitAId = regA.getVisit().getId();
        Patient patientA = patientRepository.findByIdAndIsActiveTrue(patientAId).orElseThrow();
        UUID identityId = patientA.getPersonIdentity().getId();

        // Same NID at hospital B → shares the identity (no re-registration of the person).
        patientService.registerPatientWithVisit(reg(b.getId(), "Jean", "Niyonzima", nid));

        Visit visitA = visitRepository.findById(visitAId).orElseThrow();

        diagnosisRepository.save(Diagnosis.builder()
                .visit(visitA).diagnosisType(DiagnosisType.CONFIRMED)
                .description("Sepsis secondary to pneumonia").icdCode("A41.9")
                .isPrimary(true).diagnosedByName("Dr A").diagnosedAt(Instant.now()).build());

        investigationRepository.save(Investigation.builder()
                .visit(visitA).investigationType(InvestigationType.LABORATORY)
                .testName("Serum Lactate").isCritical(true).isAbnormal(true)
                .orderedAt(Instant.now()).build());

        clinicalNoteRepository.save(ClinicalNote.builder()
                .visit(visitA).noteType(NoteType.DOCTOR_NOTE)
                .content("Started on broad-spectrum antibiotics; responding to fluids.")
                .recordedByName("Dr A").recordedAt(Instant.now()).build());

        clinicalDocumentRepository.save(ClinicalDocument.builder()
                .visit(visitA).documentType(ClinicalDocumentType.DISCHARGE_SUMMARY)
                .title("Discharge Summary").content("Sepsis treated; discharged stable.")
                .authorName("Dr A").authorRole("DOCTOR").isSigned(true).isAmendment(false).build());

        medicationAdministrationRepository.save(MedicationAdministration.builder()
                .visit(visitA).drugName("Ceftriaxone").dose("2 g").frequency("OD")
                .route(MedicationRoute.IV).prescribedAt(Instant.now()).prescribedByName("Dr A")
                .status(MedicationStatus.ADMINISTERED).prescriptionType(PrescriptionType.ONE_TIME)
                .productType(MedicationProductType.DRUG).build());

        return new Seed(nid, identityId, patientAId, visitAId);
    }

    private Hospital hospital(String suffix) {
        return hospitalRepository.save(Hospital.builder()
                .name("XH " + suffix).hospitalCode("XH-" + suffix).build());
    }

    private RegisterPatientRequest reg(UUID hospitalId, String first, String last, String nid) {
        return RegisterPatientRequest.builder()
                .firstName(first).lastName(last).nationalId(nid)
                .bloodType("O+").hospitalId(hospitalId).chiefComplaint("test").build();
    }

    private DataSharingConsentResponse grant(String nid) {
        return dataSharingConsentService.recordConsent(nid, RecordDataSharingConsentRequest.builder()
                .status(DataSharingConsentStatus.GRANTED)
                .consentGrantor(ConsentGrantor.PATIENT).grantorName("Self")
                .notes("Opted in").build());
    }

    private void assertAudited(String basisFragment) {
        boolean audited = auditLogRepository.findAll().stream()
                .anyMatch(l -> l.getAction() != null
                        && l.getAction().contains("CROSS_HOSPITAL_DEEP_RECORD_READ")
                        && l.getAction().contains(basisFragment));
        assertTrue(audited, "deep-record read must be audited with " + basisFragment);
    }

    private void authenticateAs(Role role, String first, String last) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName(first);
        u.setLastName(last);
        u.setEmail("xh@test.rw");
        u.setRole(role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }
}
