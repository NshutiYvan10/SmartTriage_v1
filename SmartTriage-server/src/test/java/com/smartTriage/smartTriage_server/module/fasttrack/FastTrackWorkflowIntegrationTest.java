package com.smartTriage.smartTriage_server.module.fasttrack;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.AccountStatus;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.FastTrackStatus;
import com.smartTriage.smartTriage_server.common.enums.FastTrackType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.CtResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackActivationRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.entity.FastTrackActivation;
import com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository;
import com.smartTriage.smartTriage_server.module.fasttrack.service.FastTrackMonitorService;
import com.smartTriage.smartTriage_server.module.fasttrack.service.FastTrackService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.security.ClinicalAuthz;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Fast Track workflow against REAL PostgreSQL (Testcontainers,
 * Flyway from scratch through V75): activation raises an OWNED
 * FAST_TRACK_ACTIVATED alert with the authenticated actor, CT produces the
 * advisory thrombolysis assessment, acknowledge silences the alert, complete
 * stamps the actor, the SLA monitor escalates a real breach (and skips a
 * discharged visit), and the cross-tenant authz guard holds. Proves the
 * controller/service ↔ JPA ↔ Flyway-schema ↔ alert wiring that unit tests
 * (with mocks) cannot. Each test runs in a rolled-back transaction.
 */
@Transactional
class FastTrackWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private FastTrackActivationRepository ftRepository;
    @Autowired private ClinicalAlertRepository alertRepository;

    @Autowired private FastTrackService fastTrackService;
    @Autowired private FastTrackMonitorService fastTrackMonitor;
    @Autowired private ClinicalAuthz clinicalAuthz;

    private Hospital hospital;
    private Visit visit;
    private User doctor;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        hospital = new Hospital();
        hospital.setName("FT Hospital " + suffix);
        hospital.setHospitalCode("FT-" + suffix);
        hospital = hospitalRepository.save(hospital);

        doctor = seedUser("dr-" + suffix, hospital);

        Patient patient = new Patient();
        patient.setFirstName("Fast");
        patient.setLastName("Track");
        patient.setHospital(hospital);
        patient = patientRepository.save(patient);

        visit = new Visit();
        visit.setPatient(patient);
        visit.setHospital(hospital);
        visit.setVisitNumber("FT-V-" + suffix);
        visit.setArrivalTime(Instant.now().minus(Duration.ofMinutes(30)));
        visit.setStatus(VisitStatus.UNDER_TREATMENT);
        visit.setCurrentEdZone(EdZone.ACUTE);
        visit = visitRepository.save(visit);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private User seedUser(String handle, Hospital h) {
        User u = new User();
        u.setFirstName(handle);
        u.setLastName("Test");
        u.setEmail(handle + "@ft.test");
        u.setPasswordHash("not-a-real-hash");
        u.setRole(Role.DOCTOR);
        u.setDesignation(Designation.MEDICAL_OFFICER);
        u.setHospital(h);
        u.setAccountStatus(AccountStatus.ACTIVE);
        return userRepository.save(u);
    }

    private void actAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private Authentication authFor(User u) {
        return new UsernamePasswordAuthenticationToken(u, null, List.of());
    }

    private FastTrackActivation activateStroke(Instant onset) {
        actAs(doctor);
        return fastTrackService.activateFastTrack(FastTrackActivationRequest.builder()
                .visitId(visit.getId())
                .fastTrackType(FastTrackType.STROKE_SUSPECTED)
                .activatedByName("SPOOFED — should be ignored")
                .symptomOnsetTime(onset)
                .build());
    }

    @Test
    void activation_persistsAndRaisesOwnedAlertWithAuthenticatedActor() {
        FastTrackActivation a = activateStroke(Instant.now().minus(Duration.ofMinutes(60)));

        FastTrackActivation reloaded = ftRepository.findByIdAndIsActiveTrue(a.getId()).orElseThrow();
        assertEquals(FastTrackStatus.ACTIVATED, reloaded.getStatus());
        // Actor is the authenticated principal — the spoofed request name is ignored.
        assertEquals(doctor.getFirstName() + " " + doctor.getLastName(), reloaded.getActivatedByName());

        // Owned, dedicated-type alert persisted for the visit.
        assertTrue(alertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.FAST_TRACK_ACTIVATED));
    }

    @Test
    void recordCt_withinWindow_persistsAdvisoryThrombolysisAssessment() {
        FastTrackActivation a = activateStroke(Instant.now().minus(Duration.ofMinutes(60)));
        fastTrackService.recordCt(a.getId(),
                CtResultRequest.builder().ctResult("No acute findings").isHemorrhagic(false).build());

        FastTrackActivation r = ftRepository.findByIdAndIsActiveTrue(a.getId()).orElseThrow();
        assertEquals(FastTrackStatus.CT_COMPLETED, r.getStatus());
        assertEquals(Boolean.TRUE, r.getThrombolysisEligible());
        assertNotNull(r.getThrombolysisAdvisory());
        assertTrue(r.getThrombolysisAdvisory().contains("ADVISORY"));
        assertNotNull(r.getDoorToCtMinutes());
    }

    @Test
    void acknowledge_stampsActivationAndSilencesTheActivationAlert() {
        FastTrackActivation a = activateStroke(Instant.now().minus(Duration.ofMinutes(60)));
        assertTrue(alertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.FAST_TRACK_ACTIVATED));

        fastTrackService.acknowledge(a.getId());

        FastTrackActivation r = ftRepository.findByIdAndIsActiveTrue(a.getId()).orElseThrow();
        assertNotNull(r.getAcknowledgedAt());
        assertNotNull(r.getAcknowledgedByName());
        // Accepting the clock must clear the unacknowledged alert (stops re-paging).
        assertFalse(alertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.FAST_TRACK_ACTIVATED));
    }

    @Test
    void complete_setsTerminalStatusAndActor() {
        FastTrackActivation a = activateStroke(Instant.now().minus(Duration.ofMinutes(60)));
        fastTrackService.complete(a.getId(), "Thrombolysed; admitted to stroke unit");

        FastTrackActivation r = ftRepository.findByIdAndIsActiveTrue(a.getId()).orElseThrow();
        assertEquals(FastTrackStatus.COMPLETED, r.getStatus());
        assertNotNull(r.getCompletedAt());
        assertEquals(doctor.getFirstName() + " " + doctor.getLastName(), r.getCompletedByName());
    }

    @Test
    void slaMonitor_raisesBreachForOpenActivation() {
        // Stroke, arrived 30 min ago, CT not done → door-to-CT (25 min) breach.
        activateStroke(Instant.now().minus(Duration.ofMinutes(60)));
        int raised = fastTrackMonitor.checkSlaBreaches();
        assertTrue(raised >= 1);
        assertTrue(alertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.FAST_TRACK_SLA_BREACH));
    }

    @Test
    void slaMonitor_skipsPatientWhoLeftTheEd() {
        activateStroke(Instant.now().minus(Duration.ofMinutes(60)));
        // Patient discharged — disposition does not auto-close the activation.
        visit.setStatus(VisitStatus.DISCHARGED);
        visitRepository.save(visit);

        fastTrackMonitor.checkSlaBreaches();
        assertFalse(alertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.FAST_TRACK_SLA_BREACH));
    }

    @Test
    void crossTenantGuard_deniesAnotherHospital_allowsOwner() {
        FastTrackActivation a = activateStroke(Instant.now().minus(Duration.ofMinutes(60)));

        Hospital other = new Hospital();
        other.setName("Other Hospital");
        other.setHospitalCode("OTHER-" + UUID.randomUUID().toString().substring(0, 6));
        other = hospitalRepository.save(other);
        User foreignDoctor = seedUser("foreign-" + UUID.randomUUID().toString().substring(0, 6), other);

        assertFalse(clinicalAuthz.canAccessFastTrack(authFor(foreignDoctor), a.getId()));
        assertTrue(clinicalAuthz.canAccessFastTrack(authFor(doctor), a.getId()));
    }
}
