package com.smartTriage.smartTriage_server.module.ems.service;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.EmsRunStatus;
import com.smartTriage.smartTriage_server.common.enums.MobilityStatus;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.TraumaStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.ems.dto.EmsRunResponse;
import com.smartTriage.smartTriage_server.module.ems.dto.FieldTriageRequest;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsInterventionRepository;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.service.UnidentifiedPatientNameService;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.triage.engine.PediatricTewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.engine.RwandaPediatricTriageDecisionEngine;
import com.smartTriage.smartTriage_server.module.triage.engine.RwandaTriageDecisionEngine;
import com.smartTriage.smartTriage_server.module.triage.engine.TewsCalculator;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import com.smartTriage.smartTriage_server.security.ClinicalAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link EmsRunService#computeFieldTriage} — the core new
 * clinical logic. Uses the REAL Rwanda/KFH triage engines (they are pure
 * @Components with no dependencies) so the test validates the actual
 * field-call computation, not a stand-in. Everything else is mocked.
 *
 * <p>The caller is a SUPER_ADMIN so {@code assertCallerMayAccess} passes
 * without hospital/ownership stubbing; the save mock echoes the entity so
 * mutations are visible on the returned run.
 */
class EmsRunServiceFieldTriageTest {

    private EmsRunRepository emsRunRepository;
    private RealTimeEventPublisher realTimeEventPublisher;
    private com.smartTriage.smartTriage_server.module.visit.service.ZoneRoutingService zoneRoutingService;
    private ClinicalAlertRepository clinicalAlertRepository;
    private ShiftAssignmentService shiftAssignmentService;
    private com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository clinicalNoteRepository;
    private EmsRunService service;

    private EmsRun run;
    private final UUID RUN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        emsRunRepository = mock(EmsRunRepository.class);
        EmsInterventionRepository interventionRepository = mock(EmsInterventionRepository.class);
        HospitalService hospitalService = mock(HospitalService.class);
        VisitService visitService = mock(VisitService.class);
        VisitRepository visitRepository = mock(VisitRepository.class);
        PatientRepository patientRepository = mock(PatientRepository.class);
        UnidentifiedPatientNameService nameService = mock(UnidentifiedPatientNameService.class);
        UserRepository userRepository = mock(UserRepository.class);
        clinicalAlertRepository = mock(ClinicalAlertRepository.class);
        when(clinicalAlertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        realTimeEventPublisher = mock(RealTimeEventPublisher.class);
        ClinicalAuthz clinicalAuthz = mock(ClinicalAuthz.class);
        HospitalRepository hospitalRepository = mock(HospitalRepository.class);
        shiftAssignmentService = mock(ShiftAssignmentService.class);
        when(shiftAssignmentService.getChargeNurse(any())).thenReturn(java.util.List.of());
        zoneRoutingService = mock(com.smartTriage.smartTriage_server.module.visit.service.ZoneRoutingService.class);
        clinicalNoteRepository = mock(com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository.class);
        when(clinicalNoteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // REAL engines — this is the whole point of the test.
        TewsCalculator tewsCalculator = new TewsCalculator();
        PediatricTewsCalculator pediatricTewsCalculator = new PediatricTewsCalculator();
        RwandaTriageDecisionEngine decisionEngine = new RwandaTriageDecisionEngine();
        RwandaPediatricTriageDecisionEngine pediatricDecisionEngine = new RwandaPediatricTriageDecisionEngine();

        service = new EmsRunService(
                emsRunRepository, interventionRepository, hospitalService, visitService,
                visitRepository, patientRepository, nameService, userRepository,
                clinicalAlertRepository, realTimeEventPublisher, clinicalAuthz,
                hospitalRepository, shiftAssignmentService,
                tewsCalculator, pediatricTewsCalculator, decisionEngine, pediatricDecisionEngine,
                mock(EmsPcrPdfService.class),
                zoneRoutingService,
                clinicalNoteRepository);

        Hospital hospital = new Hospital();
        run = EmsRun.builder()
                .hospital(hospital)
                .status(EmsRunStatus.DISPATCHED)
                .patientAgeYears(40)
                .build();

        when(emsRunRepository.findByIdAndIsActiveTrue(RUN_ID)).thenReturn(Optional.of(run));
        when(emsRunRepository.save(any(EmsRun.class))).thenAnswer(inv -> inv.getArgument(0));

        // Caller is SUPER_ADMIN → access check short-circuits.
        User admin = new User();
        admin.setRole(Role.SUPER_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Vitals all in the zero-point TEWS band: RR 9-14, HR 51-100, SBP 81-100, temp 35-38.4. */
    private FieldTriageRequest wellAdult() {
        FieldTriageRequest r = new FieldTriageRequest();
        r.setRespiratoryRate(12);
        r.setHeartRate(80);
        r.setSystolicBp(95);
        r.setSpo2(98);
        r.setTemperature(37.0);
        r.setMobility(MobilityStatus.WALKING);
        r.setAvpu(AvpuScore.ALERT);
        r.setTraumaStatus(TraumaStatus.NO_TRAUMA);
        return r;
    }

    @Test
    void wellAdult_noSigns_isGreen() {
        EmsRunResponse resp = service.computeFieldTriage(RUN_ID, wellAdult());
        assertEquals("GREEN", resp.getFieldTriageCategory());
        assertEquals(0, resp.getFieldTewsScore());
        assertEquals(Boolean.FALSE, resp.getFieldTriageIsChild());
        assertNotNull(resp.getFieldTriageDecisionPath());
    }

    @Test
    void lowSpo2_overridesToRed() {
        FieldTriageRequest r = wellAdult();
        r.setSpo2(88); // < 92 → RED regardless of TEWS
        EmsRunResponse resp = service.computeFieldTriage(RUN_ID, r);
        assertEquals("RED", resp.getFieldTriageCategory());
    }

    @Test
    void emergencySign_isRed() {
        FieldTriageRequest r = wellAdult();
        r.setHasCardiacArrest(true);
        EmsRunResponse resp = service.computeFieldTriage(RUN_ID, r);
        assertEquals("RED", resp.getFieldTriageCategory());
    }

    @Test
    void chestPain_lowTews_isOrange() {
        FieldTriageRequest r = wellAdult();
        r.setVuChestPain(true); // TEWS 0-4 + very-urgent sign → ORANGE
        EmsRunResponse resp = service.computeFieldTriage(RUN_ID, r);
        assertEquals("ORANGE", resp.getFieldTriageCategory());
    }

    @Test
    void closedFracture_lowTews_isYellow() {
        FieldTriageRequest r = wellAdult();
        r.setUrgClosedFracture(true); // TEWS 0-2 + urgent sign → YELLOW
        EmsRunResponse resp = service.computeFieldTriage(RUN_ID, r);
        assertEquals("YELLOW", resp.getFieldTriageCategory());
    }

    @Test
    void highTews_isRed() {
        FieldTriageRequest r = wellAdult();
        r.setRespiratoryRate(34);  // >29 → 3
        r.setHeartRate(135);       // >129 → 3
        r.setSystolicBp(65);       // <71 → 2
        r.setAvpu(AvpuScore.PAIN); // 2  → total ≥ 7 → RED
        EmsRunResponse resp = service.computeFieldTriage(RUN_ID, r);
        assertEquals("RED", resp.getFieldTriageCategory());
        assertTrue(resp.getFieldTewsScore() >= 7);
    }

    @Test
    void pediatric_usesPedsEngineAndFlag() {
        run.setPatientAgeYears(2); // < 13 → KFH peds engine
        FieldTriageRequest r = wellAdult();
        r.setSpo2(85); // < 92 → RED
        EmsRunResponse resp = service.computeFieldTriage(RUN_ID, r);
        assertEquals("RED", resp.getFieldTriageCategory());
        assertEquals(Boolean.TRUE, resp.getFieldTriageIsChild());
    }

    @Test
    void persistsFieldVitalsAndReason() {
        FieldTriageRequest r = wellAdult();
        r.setReason("hypotensive, suspected bleed");
        service.computeFieldTriage(RUN_ID, r);
        assertEquals(12, run.getFieldRespRate());
        assertEquals(80, run.getFieldHr());
        assertEquals(95, run.getFieldSbp());
        assertEquals("hypotensive, suspected bleed", run.getFieldTriageReason());
    }

    @Test
    void recompute_toLowerAcuity_blockedWithoutAck_allowedWithAck() {
        // First compute → RED (cardiac arrest).
        FieldTriageRequest red = wellAdult();
        red.setHasCardiacArrest(true);
        assertEquals("RED", service.computeFieldTriage(RUN_ID, red).getFieldTriageCategory());

        // Re-compute to GREEN (well, no signs) WITHOUT ack → blocked (silent-downgrade guard).
        assertThrows(ClinicalBusinessException.class,
                () -> service.computeFieldTriage(RUN_ID, wellAdult()));
        assertEquals("RED", run.getFieldTriageCategory()); // unchanged

        // With explicit acknowledgement → the downgrade is recorded.
        FieldTriageRequest green = wellAdult();
        green.setAcknowledgeDowngrade(true);
        assertEquals("GREEN", service.computeFieldTriage(RUN_ID, green).getFieldTriageCategory());
    }

    @Test
    void persistsFieldTriageInputJson() {
        service.computeFieldTriage(RUN_ID, wellAdult());
        assertNotNull(run.getFieldTriageInput());
        assertTrue(run.getFieldTriageInput().contains("mobility"));
    }

    @Test
    void setLights_activatesAndStamps() {
        EmsRunResponse on = service.setLights(RUN_ID, true);
        assertTrue(on.isLightsActive());
        assertNotNull(on.getLightsActivatedAt());

        EmsRunResponse off = service.setLights(RUN_ID, false);
        assertTrue(!off.isLightsActive());
        assertNull(off.getLightsActivatedAt());
    }

    // ── confirmArrival provisional zone placement from the field triage ──

    @Test
    void confirmArrival_seedsProvisionalPlacementFromFieldCategory() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        visit.setStatus(com.smartTriage.smartTriage_server.common.enums.VisitStatus.REGISTERED);
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.EN_ROUTE);
        run.setFieldTriageCategory("RED");
        when(zoneRoutingService.routeFor(visit,
                com.smartTriage.smartTriage_server.common.enums.TriageCategory.RED))
                .thenReturn(com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS);

        service.confirmArrival(RUN_ID);

        // Field category seeds a PROVISIONAL hospital category + zone via the same
        // ZoneRoutingService the ED uses…
        assertEquals(com.smartTriage.smartTriage_server.common.enums.TriageCategory.RED,
                visit.getCurrentTriageCategory());
        assertEquals(com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS,
                visit.getCurrentEdZone());
        // …but the visit deliberately STAYS AWAITING_TRIAGE so formal ED triage is
        // still required (and can override).
        assertEquals(com.smartTriage.smartTriage_server.common.enums.VisitStatus.AWAITING_TRIAGE,
                visit.getStatus());
    }

    @Test
    void confirmArrival_doesNotOverwriteExistingTriageCategory() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        visit.setStatus(com.smartTriage.smartTriage_server.common.enums.VisitStatus.AWAITING_TRIAGE);
        visit.setCurrentTriageCategory(
                com.smartTriage.smartTriage_server.common.enums.TriageCategory.YELLOW);
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.EN_ROUTE);
        run.setFieldTriageCategory("RED");

        service.confirmArrival(RUN_ID);

        // Guard: an already-triaged visit is never re-placed from the (older)
        // field category — the existing category is preserved and routing is skipped.
        assertEquals(com.smartTriage.smartTriage_server.common.enums.TriageCategory.YELLOW,
                visit.getCurrentTriageCategory());
        assertNull(visit.getCurrentEdZone());
    }

    @Test
    void confirmArrival_raisesEmsArrivedAlert() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        visit.setStatus(com.smartTriage.smartTriage_server.common.enums.VisitStatus.REGISTERED);
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.EN_ROUTE);
        run.setFieldTriageCategory("RED");

        service.confirmArrival(RUN_ID);

        // Arrival now raises an attention-grabbing owned alert (not just the silent
        // board card-flip). RED → CRITICAL so the client beeps.
        org.mockito.ArgumentCaptor<com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert> cap =
                org.mockito.ArgumentCaptor.forClass(
                        com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert.class);
        org.mockito.Mockito.verify(clinicalAlertRepository).save(cap.capture());
        assertEquals(com.smartTriage.smartTriage_server.common.enums.AlertType.EMS_ARRIVED,
                cap.getValue().getAlertType());
        assertEquals(com.smartTriage.smartTriage_server.common.enums.AlertSeverity.CRITICAL,
                cap.getValue().getSeverity());
    }

    @Test
    void confirmArrival_orange_placesAcuteZone_bypassesTriageDesk() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        visit.setStatus(com.smartTriage.smartTriage_server.common.enums.VisitStatus.REGISTERED);
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.EN_ROUTE);
        run.setFieldTriageCategory("ORANGE");
        when(zoneRoutingService.routeFor(visit,
                com.smartTriage.smartTriage_server.common.enums.TriageCategory.ORANGE))
                .thenReturn(com.smartTriage.smartTriage_server.common.enums.EdZone.ACUTE);

        service.confirmArrival(RUN_ID);

        // Acuity-split: ORANGE is high-acuity → placed straight into ACUTE, bypassing the
        // triage-desk queue (currentEdZone set). Still AWAITING_TRIAGE for the formal triage.
        assertEquals(com.smartTriage.smartTriage_server.common.enums.TriageCategory.ORANGE,
                visit.getCurrentTriageCategory());
        assertEquals(com.smartTriage.smartTriage_server.common.enums.EdZone.ACUTE,
                visit.getCurrentEdZone());
        assertEquals(com.smartTriage.smartTriage_server.common.enums.VisitStatus.AWAITING_TRIAGE,
                visit.getStatus());
    }

    @Test
    void confirmArrival_yellow_entersTriageQueue_noZonePlacement() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        visit.setStatus(com.smartTriage.smartTriage_server.common.enums.VisitStatus.REGISTERED);
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.EN_ROUTE);
        run.setFieldTriageCategory("YELLOW");

        service.confirmArrival(RUN_ID);

        // Acuity-split: YELLOW is lower-acuity → the field call stands as the advisory
        // category, but currentEdZone stays NULL so the patient enters the ED triage-DESK
        // queue for a formal triage like a walk-in. Routing is NOT invoked.
        assertEquals(com.smartTriage.smartTriage_server.common.enums.TriageCategory.YELLOW,
                visit.getCurrentTriageCategory());
        assertNull(visit.getCurrentEdZone());
        org.mockito.Mockito.verify(zoneRoutingService, org.mockito.Mockito.never())
                .routeFor(any(), any());
    }

    @Test
    void confirmArrival_autoAcknowledgesOpenPreArrivalAlert() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        visit.setStatus(com.smartTriage.smartTriage_server.common.enums.VisitStatus.REGISTERED);
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.EN_ROUTE);
        run.setFieldTriageCategory("YELLOW");

        com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert preArrival =
                com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert.builder()
                        .alertType(com.smartTriage.smartTriage_server.common.enums.AlertType.EMS_PRE_ARRIVAL)
                        .build();
        when(clinicalAlertRepository.findByVisitIdAndAlertTypeInAndIsAcknowledgedFalseAndIsActiveTrue(
                any(), any())).thenReturn(new java.util.ArrayList<>(List.of(preArrival)));

        service.confirmArrival(RUN_ID);

        // Issue-1 sync: arrival supersedes the en-route ping, so the open EMS_PRE_ARRIVAL
        // alert is auto-acknowledged — it must not linger in the Alert Center after arrival.
        assertTrue(preArrival.isAcknowledged());
    }

    @Test
    void transferOfCare_autoAcknowledgesEmsAlerts() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.ARRIVED);

        com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert preArrival =
                com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert.builder()
                        .alertType(com.smartTriage.smartTriage_server.common.enums.AlertType.EMS_PRE_ARRIVAL).build();
        com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert arrived =
                com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert.builder()
                        .alertType(com.smartTriage.smartTriage_server.common.enums.AlertType.EMS_ARRIVED).build();
        when(clinicalAlertRepository.findByVisitIdAndAlertTypeInAndIsAcknowledgedFalseAndIsActiveTrue(
                any(), any())).thenReturn(new java.util.ArrayList<>(List.of(preArrival, arrived)));

        service.transferOfCare(RUN_ID, new com.smartTriage.smartTriage_server.module.ems.dto.TransferOfCareRequest());

        // Issue-1 sync ("vice versa"): completing the handover from the dashboard clears
        // BOTH EMS notifications in the Alert Center — no second acknowledge needed.
        assertTrue(preArrival.isAcknowledged());
        assertTrue(arrived.isAcknowledged());
    }

    @Test
    void confirmArrival_uncategorizedWithLights_presumptivelyPlacedInResus() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        visit.setStatus(com.smartTriage.smartTriage_server.common.enums.VisitStatus.REGISTERED);
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.EN_ROUTE);
        run.setFieldTriageCategory(null);   // paramedic filed NO field triage
        run.setLightsActive(true);          // …but lights were on

        service.confirmArrival(RUN_ID);

        // Hybrid policy: uncategorised + lights → presumptively critical → straight to RESUS,
        // WITHOUT fabricating a triage category (the ED files the real one).
        assertEquals(com.smartTriage.smartTriage_server.common.enums.EdZone.RESUS,
                visit.getCurrentEdZone());
        assertNull(visit.getCurrentTriageCategory());
    }

    @Test
    void confirmArrival_uncategorizedNoLights_notPlaced_butArrivalAlertIsHigh() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        visit.setStatus(com.smartTriage.smartTriage_server.common.enums.VisitStatus.REGISTERED);
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.EN_ROUTE);
        run.setFieldTriageCategory(null);
        run.setLightsActive(false);

        service.confirmArrival(RUN_ID);

        // No zone placement (it will enter the triage-desk flow), but it must NOT look routine:
        // the arrival alert is escalated to HIGH with a "needs immediate triage" framing.
        assertNull(visit.getCurrentEdZone());
        assertNull(visit.getCurrentTriageCategory());
        org.mockito.ArgumentCaptor<com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert> cap =
                org.mockito.ArgumentCaptor.forClass(
                        com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert.class);
        org.mockito.Mockito.verify(clinicalAlertRepository).save(cap.capture());
        assertEquals(com.smartTriage.smartTriage_server.common.enums.AlertType.EMS_ARRIVED,
                cap.getValue().getAlertType());
        assertEquals(com.smartTriage.smartTriage_server.common.enums.AlertSeverity.HIGH,
                cap.getValue().getSeverity());
    }

    @Test
    void acknowledgeArrival_stampsReceipt_advancesToReceived_andAutoAcksAlerts() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.ARRIVED);

        com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert arrived =
                com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert.builder()
                        .alertType(com.smartTriage.smartTriage_server.common.enums.AlertType.EMS_ARRIVED).build();
        when(clinicalAlertRepository.findByVisitIdAndAlertTypeInAndIsAcknowledgedFalseAndIsActiveTrue(
                any(), any())).thenReturn(new java.util.ArrayList<>(List.of(arrived)));

        EmsRunResponse resp = service.acknowledgeArrival(RUN_ID);

        // Receipt is stamped (who/when), the case advances to RECEIVED, and the open
        // EMS alert is auto-acknowledged — one action on the card clears the Alert Center.
        assertNotNull(run.getArrivalAckedAt());
        assertTrue(arrived.isAcknowledged());
        assertEquals("RECEIVED", resp.getLifecycleStage());
    }

    @Test
    void acknowledgeArrival_isIdempotent_firstAckWins() {
        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.ARRIVED);
        java.time.Instant first = java.time.Instant.now().minusSeconds(120);
        run.setArrivalAckedAt(first);
        run.setArrivalAckedByName("First Nurse");

        service.acknowledgeArrival(RUN_ID);

        assertEquals(first, run.getArrivalAckedAt());          // not overwritten
        assertEquals("First Nurse", run.getArrivalAckedByName());
    }

    @Test
    void acknowledgeArrival_rejectedWhenNotAtDoor() {
        run.setStatus(EmsRunStatus.EN_ROUTE);
        assertThrows(ClinicalBusinessException.class, () -> service.acknowledgeArrival(RUN_ID));
    }

    @Test
    void transferOfCare_writesImmutableHandoverAttestation() {
        // Override the principal with an identified receiver (still SUPER_ADMIN so
        // the access check short-circuits) so we can assert server-attribution.
        UUID receiverId = UUID.randomUUID();
        User receiver = new User();
        receiver.setId(receiverId);
        receiver.setRole(Role.SUPER_ADMIN);
        receiver.setFirstName("Aline");
        receiver.setLastName("Uwase");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(receiver, null, List.of()));

        com.smartTriage.smartTriage_server.module.visit.entity.Visit visit =
                new com.smartTriage.smartTriage_server.module.visit.entity.Visit();
        run.setVisit(visit);
        run.setStatus(EmsRunStatus.ARRIVED);
        run.setParamedicName("P. Mugisha");
        run.setFieldTriageCategory("ORANGE");

        com.smartTriage.smartTriage_server.module.ems.dto.TransferOfCareRequest req =
                new com.smartTriage.smartTriage_server.module.ems.dto.TransferOfCareRequest();
        req.setAcknowledgementText("RTA, splinted, IV running");

        service.transferOfCare(RUN_ID, req);

        // An append-only HANDOVER note is written, principal-attributed (author from
        // the authenticated caller, not client-supplied) with the read-back captured.
        org.mockito.ArgumentCaptor<com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote> cap =
                org.mockito.ArgumentCaptor.forClass(
                        com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote.class);
        org.mockito.Mockito.verify(clinicalNoteRepository).save(cap.capture());
        com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote note = cap.getValue();
        assertEquals(com.smartTriage.smartTriage_server.common.enums.NoteType.HANDOVER, note.getNoteType());
        assertEquals(receiverId, note.getAuthorUserId());
        assertTrue(note.getContent().contains("RTA, splinted, IV running"));
        assertTrue(note.getContent().contains("transfer of care accepted"));
    }
}
