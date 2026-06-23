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
        ClinicalAlertRepository clinicalAlertRepository = mock(ClinicalAlertRepository.class);
        realTimeEventPublisher = mock(RealTimeEventPublisher.class);
        ClinicalAuthz clinicalAuthz = mock(ClinicalAuthz.class);
        HospitalRepository hospitalRepository = mock(HospitalRepository.class);
        ShiftAssignmentService shiftAssignmentService = mock(ShiftAssignmentService.class);

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
                mock(EmsPcrPdfService.class));

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
}
