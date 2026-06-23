package com.smartTriage.smartTriage_server.module.consent;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.consent.dto.BreakTheGlassEventResponse;
import com.smartTriage.smartTriage_server.module.consent.service.BreakTheGlassEventService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalDeepRecordResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.service.CrossHospitalDeepRecordService;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (real PostgreSQL): a break-the-glass override is attributed to the actor's hospital,
 * surfaces in that hospital's governance feed, and can be acknowledged there — while a cross-hospital
 * acknowledge is denied and the forensic facts survive the sign-off untouched.
 */
@Transactional
class BreakTheGlassGovernanceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientService patientService;
    @Autowired private CrossHospitalDeepRecordService deepRecordService;
    @Autowired private BreakTheGlassEventService governanceService;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void breakGlass_isAttributedToActorHospital_feedShowsIt_andAcknowledgeIsScopedAndNonDestructive() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String nid = "11992" + s;
        Hospital a = hospital("A-" + s);
        Hospital b = hospital("B-" + s);

        // A persisted clinician at hospital A — so findHospitalIdByUserId resolves to A.
        User doctor = persistDoctor(a, "doc-" + s + "@a.rw");

        // Same NID registered at A and B → shared identity.
        patientService.registerPatientWithVisit(reg(a.getId(), "Paul", "Mugisha", nid));
        patientService.registerPatientWithVisit(reg(b.getId(), "Paul", "Mugisha", nid));

        // The doctor breaks the glass (no consent on file).
        authenticateAs(doctor);
        CrossHospitalDeepRecordResponse deep = deepRecordService.getByNationalId(
                nid, "Unconscious trauma; prior allergies and surgical history needed urgently");
        assertTrue(deep.isAccessGranted());
        assertEquals("BREAK_THE_GLASS", deep.getAccessBasis());

        // The override appears in hospital A's governance feed, attributed to the actor.
        Page<BreakTheGlassEventResponse> feed =
                governanceService.getEventsForHospital(a.getId(), "all", PageRequest.of(0, 50));
        assertEquals(1, feed.getTotalElements(), "the override must surface in the actor-hospital feed");
        BreakTheGlassEventResponse evt = feed.getContent().get(0);
        assertEquals(a.getId(), evt.getActorHospitalId());
        assertEquals("NONE", evt.getPriorConsentState());
        assertTrue(evt.getReason().contains("Unconscious trauma"));
        assertFalse(evt.isAcknowledged());
        assertTrue(evt.getMaskedNationalId().startsWith("***"), "the patient NID is masked in governance");

        // Hospital B (where the override was NOT performed) cannot see it.
        Page<BreakTheGlassEventResponse> feedB =
                governanceService.getEventsForHospital(b.getId(), "all", PageRequest.of(0, 50));
        assertEquals(0, feedB.getTotalElements(), "another hospital must not see this override");

        // A governance reviewer signs it off at hospital A.
        BreakTheGlassEventResponse acked =
                governanceService.acknowledgeEvent(evt.getId(), a.getId(), "Reviewed — justified emergency");
        assertTrue(acked.isAcknowledged());
        assertEquals(doctor.getFirstName() + " " + doctor.getLastName(), acked.getAcknowledgedByName());
        assertNotNull(acked.getAcknowledgedAt());
        // Forensic facts unchanged by the sign-off.
        assertEquals("NONE", acked.getPriorConsentState());
        assertTrue(acked.getReason().contains("Unconscious trauma"));

        // A cross-hospital acknowledge (hospital B) is denied.
        assertThrows(AccessDeniedException.class,
                () -> governanceService.acknowledgeEvent(evt.getId(), b.getId(), "not my hospital"));
    }

    // ── fixtures ──
    private Hospital hospital(String suffix) {
        return hospitalRepository.save(Hospital.builder()
                .name("BTG " + suffix).hospitalCode("BTG-" + suffix).build());
    }

    private User persistDoctor(Hospital hospital, String email) {
        User u = new User();
        u.setFirstName("Grace");
        u.setLastName("Habimana");
        u.setEmail(email);
        u.setPasswordHash("x");
        u.setRole(Role.DOCTOR);
        u.setHospital(hospital);
        return userRepository.save(u);
    }

    private RegisterPatientRequest reg(UUID hospitalId, String first, String last, String nid) {
        return RegisterPatientRequest.builder()
                .firstName(first).lastName(last).nationalId(nid)
                .bloodType("O+").hospitalId(hospitalId).chiefComplaint("test").build();
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
