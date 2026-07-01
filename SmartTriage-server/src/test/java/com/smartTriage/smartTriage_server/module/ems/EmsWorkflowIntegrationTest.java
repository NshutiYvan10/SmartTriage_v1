package com.smartTriage.smartTriage_server.module.ems;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.AccountStatus;
import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.EmsRunStatus;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.alert.service.ClinicalAlertService;
import com.smartTriage.smartTriage_server.module.ems.dto.CreateEmsRunRequest;
import com.smartTriage.smartTriage_server.module.ems.dto.EmsRunResponse;
import com.smartTriage.smartTriage_server.module.ems.dto.FieldTriageRequest;
import com.smartTriage.smartTriage_server.module.ems.dto.PreregisterRequest;
import com.smartTriage.smartTriage_server.module.ems.dto.RerouteRequest;
import com.smartTriage.smartTriage_server.module.ems.dto.TransferOfCareRequest;
import com.smartTriage.smartTriage_server.module.ems.service.EmsPcrPdfService;
import com.smartTriage.smartTriage_server.module.ems.service.EmsRunService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end paramedic / ambulance workflow against REAL PostgreSQL
 * (Testcontainers, Flyway from scratch): create run → engine-computed
 * field triage → pre-arrival (placeholder patient + routed alert) →
 * confirm arrival (re-triage clock) → transfer of care; plus the
 * unidentified-placeholder and mid-transport reroute paths. Each test
 * runs in a rolled-back transaction.
 */
@Transactional
class EmsWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalAlertRepository alertRepository;
    @Autowired private EmsRunService emsRunService;
    @Autowired private ClinicalAlertService clinicalAlertService;

    private Hospital hospital;
    private User paramedic;
    private User nurse;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        hospital = newHospital("IT EMS " + suffix, "EMS-" + suffix);
        paramedic = seedUser("medic-" + suffix, Role.PARAMEDIC, Designation.PARAMEDIC, hospital);
        nurse = seedUser("rn-" + suffix, Role.NURSE, Designation.STAFF_NURSE, hospital);
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

    private User seedUser(String handle, Role role, Designation designation, Hospital h) {
        User u = new User();
        u.setFirstName(handle);
        u.setLastName("Test");
        u.setEmail(handle + "@it.test");
        u.setPasswordHash("not-a-real-hash");
        u.setRole(role);
        u.setDesignation(designation);
        u.setHospital(h);
        u.setAccountStatus(AccountStatus.ACTIVE);
        return userRepository.save(u);
    }

    private void actAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private EmsRunResponse newRun(Hospital h) {
        return emsRunService.createRun(CreateEmsRunRequest.builder()
                .hospitalId(h.getId())
                .mechanism("RTA — motorcycle vs car")
                .patientAgeYears(35)
                .build());
    }

    // ════════════════════════════════════════════════════════════════

    @Test
    void fullFlow_computeTriage_preArrival_confirm_handover() {
        actAs(paramedic);
        EmsRunResponse run = newRun(hospital);
        assertEquals(EmsRunStatus.DISPATCHED, run.getStatus());

        // Engine-computed RED field triage.
        EmsRunResponse triaged = emsRunService.computeFieldTriage(run.getId(),
                FieldTriageRequest.builder().hasCardiacArrest(true).build());
        assertEquals("RED", triaged.getFieldTriageCategory());
        assertNotNull(triaged.getFieldTriageDecisionPath());

        // Pre-arrival ping creates a placeholder visit + a CRITICAL, RESUS-routed alert.
        EmsRunResponse enRoute = emsRunService.preregister(run.getId(),
                PreregisterRequest.builder().etaMinutes(6).build());
        assertEquals(EmsRunStatus.EN_ROUTE, enRoute.getStatus());
        assertNotNull(enRoute.getVisitId());

        Visit visit = visitRepository.findById(enRoute.getVisitId()).orElseThrow();
        assertEquals(VisitStatus.REGISTERED, visit.getStatus());
        assertTrue(visit.isAmbulancePreArrival());

        ClinicalAlert preArrival = alertRepository
                .findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(visit.getId(), PageRequest.of(0, 10))
                .getContent().stream()
                .filter(a -> a.getAlertType() == AlertType.EMS_PRE_ARRIVAL)
                .findFirst().orElseThrow();
        assertEquals(AlertSeverity.CRITICAL, preArrival.getSeverity()); // RED → critical
        assertEquals(EdZone.RESUS, preArrival.getTargetZone());

        // Arrival starts the re-triage clock + advances REGISTERED → AWAITING_TRIAGE.
        EmsRunResponse arrived = emsRunService.confirmArrival(run.getId());
        assertEquals(EmsRunStatus.ARRIVED, arrived.getStatus());
        Visit afterArrival = visitRepository.findById(enRoute.getVisitId()).orElseThrow();
        assertEquals(VisitStatus.AWAITING_TRIAGE, afterArrival.getStatus());
        assertNotNull(afterArrival.getEdRetriageDueAt());
        // RED arrival → tighter 5-min re-triage fuse (not the default 15).
        assertEquals(java.time.Duration.ofMinutes(5),
                java.time.Duration.between(afterArrival.getArrivalConfirmedAt(), afterArrival.getEdRetriageDueAt()));

        // Receiving nurse (not the paramedic) acknowledges handover.
        actAs(nurse);
        EmsRunResponse handed = emsRunService.transferOfCare(run.getId(),
                TransferOfCareRequest.builder().receivedByName("RN Test").build());
        assertEquals(EmsRunStatus.HANDED_OFF, handed.getStatus());
    }

    @Test
    void preArrival_withoutPatientId_createsUnidentifiedPlaceholder() {
        actAs(paramedic);
        EmsRunResponse run = newRun(hospital);
        EmsRunResponse enRoute = emsRunService.preregister(run.getId(), new PreregisterRequest());

        Visit visit = visitRepository.findById(enRoute.getVisitId()).orElseThrow();
        Patient p = visit.getPatient();
        assertTrue(p.isUnidentified());
        assertEquals("Unknown", p.getFirstName());
        assertNotNull(p.getPlaceholderLabel());
    }

    @Test
    void preArrival_withKnownPatient_linksExisting() {
        Patient known = new Patient();
        known.setFirstName("Jean");
        known.setLastName("Bosco");
        known.setHospital(hospital);
        known = patientRepository.save(known);

        actAs(paramedic);
        EmsRunResponse run = newRun(hospital);
        EmsRunResponse enRoute = emsRunService.preregister(run.getId(),
                PreregisterRequest.builder().patientId(known.getId()).build());

        Visit visit = visitRepository.findById(enRoute.getVisitId()).orElseThrow();
        assertEquals(known.getId(), visit.getPatient().getId());
        assertFalse(visit.getPatient().isUnidentified());
    }

    @Test
    void reroute_unidentified_movesVisitToNewHospital() {
        Hospital other = newHospital("IT EMS Dest", "EMS-DEST-" + UUID.randomUUID().toString().substring(0, 6));

        actAs(paramedic);
        EmsRunResponse run = newRun(hospital);
        EmsRunResponse enRoute = emsRunService.preregister(run.getId(), new PreregisterRequest());
        UUID oldVisitId = enRoute.getVisitId();
        assertNotNull(oldVisitId);

        EmsRunResponse rerouted = emsRunService.reroute(run.getId(),
                RerouteRequest.builder().hospitalId(other.getId()).reason("closest ED on divert").build());

        assertEquals(other.getId(), rerouted.getHospitalId());
        assertEquals(EmsRunStatus.EN_ROUTE, rerouted.getStatus());
        assertNotNull(rerouted.getVisitId());
        assertNotEquals(oldVisitId, rerouted.getVisitId());

        // Old visit stood down (transferred + inactive); new visit lives at the new hospital.
        Visit oldVisit = visitRepository.findById(oldVisitId).orElseThrow();
        assertEquals(VisitStatus.TRANSFERRED, oldVisit.getStatus());
        assertFalse(oldVisit.isActive());

        Visit newVisit = visitRepository.findById(rerouted.getVisitId()).orElseThrow();
        assertEquals(other.getId(), newVisit.getHospital().getId());
    }

    @Test
    void reroute_identifiedPreRegisteredPatient_isBlocked() {
        Hospital other = newHospital("IT EMS Dest2", "EMS-D2-" + UUID.randomUUID().toString().substring(0, 6));
        Patient known = new Patient();
        known.setFirstName("Aline");
        known.setLastName("Mukamana");
        known.setHospital(hospital);
        known = patientRepository.save(known);

        actAs(paramedic);
        EmsRunResponse run = newRun(hospital);
        emsRunService.preregister(run.getId(),
                PreregisterRequest.builder().patientId(known.getId()).build());

        // Patient row is hospital-scoped — a silent cross-hospital move is refused.
        assertThrows(ClinicalBusinessException.class, () ->
                emsRunService.reroute(run.getId(), RerouteRequest.builder().hospitalId(other.getId()).build()));
    }

    @Test
    void pcr_rendersForOwnerParamedic_andDeniesAnotherParamedic() {
        actAs(paramedic);
        EmsRunResponse run = newRun(hospital);
        emsRunService.computeFieldTriage(run.getId(),
                FieldTriageRequest.builder().hasCardiacArrest(true).build());

        // The owning paramedic gets a valid PCR PDF.
        EmsPcrPdfService.RenderedPdf pdf = emsRunService.renderPcr(run.getId(), "Owner Paramedic");
        assertTrue(pdf.bytes().length > 800, "PCR PDF should be non-trivial");
        assertEquals("%PDF", new String(pdf.bytes(), 0, 4, StandardCharsets.US_ASCII), "PDF magic bytes");
        assertTrue(pdf.filename().startsWith("pcr-"), "filename is the PCR convention");

        // A DIFFERENT paramedic must NOT render this run's PCR (owner-scope authz on the new path).
        User otherMedic = seedUser("medic2-" + UUID.randomUUID().toString().substring(0, 8),
                Role.PARAMEDIC, Designation.PARAMEDIC, hospital);
        actAs(otherMedic);
        assertThrows(AccessDeniedException.class, () -> emsRunService.renderPcr(run.getId(), "Other Paramedic"));
    }

    @Test
    void preArrivalAck_stampsRunSoParamedicSeesItWasReceived() {
        actAs(paramedic);
        EmsRunResponse run = newRun(hospital);
        EmsRunResponse enRoute = emsRunService.preregister(run.getId(), new PreregisterRequest());

        ClinicalAlert preArrival = alertRepository
                .findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(enRoute.getVisitId(), PageRequest.of(0, 10))
                .getContent().stream()
                .filter(a -> a.getAlertType() == AlertType.EMS_PRE_ARRIVAL)
                .findFirst().orElseThrow();

        // The receiving nurse acknowledges the inbound.
        actAs(nurse);
        clinicalAlertService.acknowledgeAlert(preArrival.getId(), "Prepping bay");

        // The paramedic's run now reflects that the ED received it.
        EmsRunResponse refreshed = emsRunService.getById(run.getId());
        assertNotNull(refreshed.getPreArrivalAckedAt());
        assertNotNull(refreshed.getPreArrivalAckedByName());
    }
}
