package com.smartTriage.smartTriage_server.module.patient;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.AllergySeverity;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.audit.repository.AuditLogRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalSafetySummaryResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PatientAllergy;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientAllergyRepository;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.service.CrossHospitalIdentityService;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against REAL PostgreSQL: a patient registered with a national ID at hospital A and
 * again at hospital B links to ONE shared {@link com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity},
 * and the cross-hospital safety summary read at B surfaces the allergy recorded at A (tagged with
 * its source hospital) — proving Phase 1: shared identity + minimal safety summary, no
 * re-registration, deep records still hospital-local. Also: blank-NID patients are never linked,
 * and each cross-hospital read is audited.
 */
@Transactional
class CrossHospitalIdentityIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private PatientService patientService;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientAllergyRepository patientAllergyRepository;
    @Autowired private CrossHospitalIdentityService crossHospitalIdentityService;
    @Autowired private AuditLogRepository auditLogRepository;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private Hospital hospital(String suffix) {
        return hospitalRepository.save(Hospital.builder()
                .name("XH " + suffix).hospitalCode("XH-" + suffix).build());
    }

    private RegisterPatientRequest reg(UUID hospitalId, String first, String last, String nid) {
        return RegisterPatientRequest.builder()
                .firstName(first).lastName(last).nationalId(nid)
                .bloodType("O+").hospitalId(hospitalId).chiefComplaint("test").build();
    }

    @Test
    void sameNationalId_atTwoHospitals_sharesIdentity_andSafetySummaryCrossesHospitals() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String nid = "11990" + s;
        Hospital a = hospital("A-" + s);
        Hospital b = hospital("B-" + s);

        // Register at hospital A.
        UUID patientAId = patientService.registerPatientWithVisit(reg(a.getId(), "Marie", "Uwimana", nid))
                .getPatient().getId();
        Patient patientA = patientRepository.findByIdAndIsActiveTrue(patientAId).orElseThrow();
        assertNotNull(patientA.getPersonIdentity(), "patient A must be linked to a shared identity");
        UUID identityA = patientA.getPersonIdentity().getId();

        // Register the SAME national ID at hospital B (a different hospital).
        UUID patientBId = patientService.registerPatientWithVisit(reg(b.getId(), "Marie", "Uwimana", nid))
                .getPatient().getId();
        Patient patientB = patientRepository.findByIdAndIsActiveTrue(patientBId).orElseThrow();
        assertNotNull(patientB.getPersonIdentity(), "patient B must be linked");
        assertEquals(identityA, patientB.getPersonIdentity().getId(),
                "both hospitals' local rows must share ONE identity");
        assertNotEqualsId(patientAId, patientBId); // two distinct local records

        // Record a SEVERE allergy at hospital A.
        patientAllergyRepository.save(PatientAllergy.builder()
                .patient(patientA).allergenName("Penicillin").severity(AllergySeverity.SEVERE)
                .reaction("anaphylaxis").recordedByName("Dr A").recordedAt(Instant.now()).build());

        // A clinician at hospital B looks the person up by national ID.
        authenticateAs(Role.DOCTOR, "Grace", "Habimana");
        CrossHospitalSafetySummaryResponse summary = crossHospitalIdentityService.getByNationalId(nid);

        assertTrue(summary.isFound(), "the person must be found cross-hospital");
        assertEquals("Marie", summary.getFirstName());
        assertEquals(2, summary.getLinkedHospitalCount(), "summary spans both hospitals");
        assertFalse(summary.getAllergies().isEmpty(), "the allergy recorded at A must surface at B");
        assertTrue(summary.getAllergies().stream().anyMatch(i -> i.getDetail().contains("Penicillin")),
                "Penicillin allergy must appear");
        assertTrue(summary.getAllergies().stream().anyMatch(i -> i.getSourceHospital().contains("XH-A-" + s)
                        || i.getSourceHospital().contains("XH A-" + s)),
                "the allergy must be tagged with its source hospital (provenance)");

        // The cross-hospital read is audited.
        boolean audited = auditLogRepository.findAll().stream()
                .anyMatch(l -> l.getAction() != null && l.getAction().contains("CROSS_HOSPITAL_SAFETY_SUMMARY_READ"));
        assertTrue(audited, "every cross-hospital safety-summary read must be audited");
    }

    @Test
    void blankNationalId_patientIsNotLinked_andSummaryNotFound() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("C-" + s);
        UUID pid = patientService.registerPatientWithVisit(reg(a.getId(), "Unknown", "Alpha", null))
                .getPatient().getId();
        Patient p = patientRepository.findByIdAndIsActiveTrue(pid).orElseThrow();
        assertTrue(p.getPersonIdentity() == null, "a patient with no national ID must NOT be linked");

        CrossHospitalSafetySummaryResponse summary = crossHospitalIdentityService.getByNationalId("   ");
        assertFalse(summary.isFound(), "blank national ID → not found");
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

    private void assertNotEqualsId(UUID a, UUID b) {
        assertFalse(a.equals(b), "the two hospitals must hold distinct local patient rows");
    }
}
