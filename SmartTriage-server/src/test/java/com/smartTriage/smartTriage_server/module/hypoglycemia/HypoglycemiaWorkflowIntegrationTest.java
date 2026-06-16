package com.smartTriage.smartTriage_server.module.hypoglycemia;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.AccountStatus;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.enums.VitalSource;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.RecordTreatmentRequest;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.RepeatGlucoseRequest;
import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.service.HypoglycemiaRecheckMonitorService;
import com.smartTriage.smartTriage_server.module.hypoglycemia.service.HypoglycemiaService;
import com.smartTriage.smartTriage_server.security.ClinicalAuthz;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end hypoglycemia workflow against REAL PostgreSQL (Testcontainers,
 * Flyway through V76): a low POC glucose recorded as a VITAL auto-creates an
 * OWNED HYPOGLYCEMIA_CRITICAL alert + an unresolved event (the dead-detector /
 * frozen-triage-snapshot fix), treatment records the AUTHENTICATED actor + arms
 * the recheck clock, a recovered repeat resolves it, the recheck monitor escalates
 * an overdue recheck, and the cross-tenant authz guard holds. Each test rolls back.
 */
@Transactional
class HypoglycemiaWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private HypoglycemiaEventRepository eventRepository;
    @Autowired private ClinicalAlertRepository alertRepository;

    @Autowired private VitalSignsService vitalSignsService;
    @Autowired private HypoglycemiaService hypoglycemiaService;
    @Autowired private HypoglycemiaRecheckMonitorService recheckMonitor;
    @Autowired private ClinicalAuthz clinicalAuthz;

    private Hospital hospital;
    private Visit visit;
    private User doctor;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        hospital = new Hospital();
        hospital.setName("HG Hospital " + suffix);
        hospital.setHospitalCode("HG-" + suffix);
        hospital = hospitalRepository.save(hospital);

        doctor = seedUser("dr-" + suffix, hospital);

        Patient patient = new Patient();
        patient.setFirstName("Hypo");
        patient.setLastName("Glycemia");
        patient.setHospital(hospital);
        patient.setDateOfBirth(LocalDate.now().minusYears(50));
        patient = patientRepository.save(patient);

        visit = new Visit();
        visit.setPatient(patient);
        visit.setHospital(hospital);
        visit.setVisitNumber("HG-V-" + suffix);
        visit.setArrivalTime(Instant.now().minus(Duration.ofMinutes(20)));
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
        u.setEmail(handle + "@hg.test");
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

    /** Record a low POC glucose as a vital — the auto-trigger should create the event. */
    private HypoglycemiaEvent recordLowGlucoseVital(double glucose) {
        actAs(doctor);
        vitalSignsService.recordVitals(RecordVitalsRequest.builder()
                .visitId(visit.getId())
                .heartRate(96)
                .bloodGlucose(glucose)
                .avpu(AvpuScore.ALERT)
                .source(VitalSource.MANUAL_ENTRY)
                .build());
        List<HypoglycemiaEvent> events =
                eventRepository.findByVisitIdAndIsActiveTrueOrderByDetectedAtDesc(visit.getId());
        return events.isEmpty() ? null : events.get(0);
    }

    @Test
    void lowGlucoseVital_autoCreatesOwnedAlertAndEvent() {
        HypoglycemiaEvent event = recordLowGlucoseVital(1.9);

        assertNotNull(event, "a low POC glucose vital must auto-create a hypoglycemia event");
        assertEquals("SEVERE", event.getSeverity());
        assertEquals("MANUAL_VITALS", event.getGlucoseSource());
        assertNotNull(event.getRecheckDueAt());
        assertEquals(doctor.getFirstName() + " " + doctor.getLastName(), event.getDetectedByName());
        // Owned, dedicated-type alert persisted.
        assertTrue(alertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.HYPOGLYCEMIA_CRITICAL));
    }

    @Test
    void mgDlGlucoseVital_convertsToMmolAndDetects() {
        // A glucometer reading of 36 mg/dL = 2.0 mmol/L (SEVERE). Without unit-aware
        // conversion "36" would be read as 36 mmol/L (NORMAL) and the patient missed.
        actAs(doctor);
        vitalSignsService.recordVitals(RecordVitalsRequest.builder()
                .visitId(visit.getId())
                .bloodGlucose(36.0)
                .bloodGlucoseUnit(com.smartTriage.smartTriage_server.common.enums.GlucoseUnit.MG_DL)
                .avpu(AvpuScore.ALERT)
                .source(VitalSource.MANUAL_ENTRY)
                .build());

        List<HypoglycemiaEvent> events =
                eventRepository.findByVisitIdAndIsActiveTrueOrderByDetectedAtDesc(visit.getId());
        assertFalse(events.isEmpty(), "a 36 mg/dL reading must convert to 2.0 mmol/L and detect");
        assertEquals("SEVERE", events.get(0).getSeverity());
        assertEquals(2.0, events.get(0).getGlucoseLevel(), 0.01);
    }

    @Test
    void normalGlucoseVital_createsNoEvent() {
        HypoglycemiaEvent event = recordLowGlucoseVital(6.0);
        org.junit.jupiter.api.Assertions.assertNull(event);
        assertFalse(alertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.HYPOGLYCEMIA_CRITICAL));
    }

    @Test
    void treatment_recordsAuthenticatedActorAndArmsRecheck() {
        HypoglycemiaEvent event = recordLowGlucoseVital(2.0);
        assertNotNull(event);

        actAs(doctor);
        hypoglycemiaService.recordTreatment(event.getId(),
                RecordTreatmentRequest.builder().treatment("IV Dextrose 50% 50ml").build());

        HypoglycemiaEvent reloaded = eventRepository.findByIdAndIsActiveTrue(event.getId()).orElseThrow();
        assertEquals(doctor.getFirstName() + " " + doctor.getLastName(), reloaded.getTreatmentGivenByName());
        assertNotNull(reloaded.getRecheckDueAt());
    }

    @Test
    void recoveredRepeatGlucose_resolvesEvent() {
        HypoglycemiaEvent event = recordLowGlucoseVital(2.0);
        assertNotNull(event);

        actAs(doctor);
        hypoglycemiaService.recordRepeatGlucose(event.getId(),
                RepeatGlucoseRequest.builder().glucoseLevel(5.0).build());

        HypoglycemiaEvent reloaded = eventRepository.findByIdAndIsActiveTrue(event.getId()).orElseThrow();
        assertTrue(reloaded.isResolved());
        assertEquals(doctor.getFirstName() + " " + doctor.getLastName(), reloaded.getResolvedByName());
    }

    @Test
    void recheckMonitor_escalatesOverdueRecheck() {
        HypoglycemiaEvent event = recordLowGlucoseVital(2.0);
        assertNotNull(event);
        // Force the recheck due time into the past.
        event.setRecheckDueAt(Instant.now().minus(Duration.ofMinutes(5)));
        eventRepository.save(event);

        recheckMonitor.checkRecheckOverdue();

        assertTrue(alertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.HYPOGLYCEMIA_RECHECK_OVERDUE));
    }

    @Test
    void crossTenantGuard_deniesAnotherHospital_allowsOwner() {
        HypoglycemiaEvent event = recordLowGlucoseVital(2.0);
        assertNotNull(event);

        Hospital other = new Hospital();
        other.setName("Other Hospital");
        other.setHospitalCode("OTHER-" + UUID.randomUUID().toString().substring(0, 6));
        other = hospitalRepository.save(other);
        User foreignDoctor = seedUser("foreign-" + UUID.randomUUID().toString().substring(0, 6), other);

        assertFalse(clinicalAuthz.canAccessHypoglycemiaEvent(authFor(foreignDoctor), event.getId()));
        assertTrue(clinicalAuthz.canAccessHypoglycemiaEvent(authFor(doctor), event.getId()));
    }
}
