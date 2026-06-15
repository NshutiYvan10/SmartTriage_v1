package com.smartTriage.smartTriage_server.module.patient;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.AccountStatus;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.patient.dto.ResolveIdentityRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.service.PatientIdentityService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Identity-resolution safety invariants against REAL PostgreSQL: the
 * unidentified-placeholder → real-identity loop must preserve the patient
 * UUID (so visits/triage/alerts stay valid), persist the audit note, and
 * refuse already-identified + cross-hospital merges.
 */
@Transactional
class PatientIdentityResolutionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientIdentityService identityService;

    private Hospital hospital;
    private User nurse;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        hospital = newHospital("IT Identity " + suffix, "IDN-" + suffix);
        nurse = new User();
        nurse.setFirstName("RN-" + suffix);
        nurse.setLastName("Test");
        nurse.setEmail("rn-" + suffix + "@it.test");
        nurse.setPasswordHash("not-a-real-hash");
        nurse.setRole(Role.NURSE);
        nurse.setDesignation(Designation.STAFF_NURSE);
        nurse.setHospital(hospital);
        nurse.setAccountStatus(AccountStatus.ACTIVE);
        nurse = userRepository.save(nurse);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(nurse, null, List.of()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private Hospital newHospital(String name, String code) {
        Hospital h = new Hospital();
        h.setName(name);
        h.setHospitalCode(code);
        return hospitalRepository.save(h);
    }

    private Patient placeholderAt(Hospital h, String label) {
        Patient p = Patient.builder()
                .firstName("Unknown")
                .lastName(label)
                .hospital(h)
                .isUnidentified(true)
                .placeholderLabel(label)
                .placeholderAssignedAt(Instant.now())
                .build();
        return patientRepository.save(p);
    }

    @Test
    void rename_preservesUuid_persistsNote_flipsIdentified() {
        Patient ph = placeholderAt(hospital, "Alpha");
        UUID originalId = ph.getId();

        Patient resolved = identityService.resolveIdentity(ph.getId(), ResolveIdentityRequest.builder()
                .firstName("Marie").lastName("Uwimana")
                .resolutionNote("Family arrived with national ID")
                .build());

        assertEquals(originalId, resolved.getId()); // UUID preserved — references stay valid

        Patient reloaded = patientRepository.findById(originalId).orElseThrow();
        assertFalse(reloaded.isUnidentified());
        assertNotNull(reloaded.getIdentifiedAt());
        assertEquals("Marie", reloaded.getFirstName());
        assertEquals("Uwimana", reloaded.getLastName());
        assertEquals("Family arrived with national ID", reloaded.getResolutionNote());
    }

    @Test
    void resolvingAnAlreadyIdentifiedPatient_isRejected() {
        Patient ph = placeholderAt(hospital, "Bravo");
        identityService.resolveIdentity(ph.getId(),
                ResolveIdentityRequest.builder().firstName("Jean").lastName("Bosco").build());

        assertThrows(ClinicalBusinessException.class, () ->
                identityService.resolveIdentity(ph.getId(),
                        ResolveIdentityRequest.builder().firstName("Other").lastName("Name").build()));
    }

    @Test
    void mergeAcrossHospitals_isRejected() {
        Hospital other = newHospital("IT Identity Dest", "IDN-D-" + UUID.randomUUID().toString().substring(0, 6));
        Patient ph = placeholderAt(hospital, "Charlie");
        Patient targetElsewhere = patientRepository.save(Patient.builder()
                .firstName("Existing").lastName("Patient").hospital(other).build());

        assertThrows(ClinicalBusinessException.class, () ->
                identityService.resolveIdentity(ph.getId(),
                        ResolveIdentityRequest.builder().mergeIntoPatientId(targetElsewhere.getId()).build()));
    }
}
