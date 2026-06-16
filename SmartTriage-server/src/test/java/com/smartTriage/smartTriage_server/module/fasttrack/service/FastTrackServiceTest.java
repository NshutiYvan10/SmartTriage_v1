package com.smartTriage.smartTriage_server.module.fasttrack.service;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.FastTrackStatus;
import com.smartTriage.smartTriage_server.common.enums.FastTrackType;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.CtResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.EcgResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackActivationRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.engine.StrokeMIDetectionEngine;
import com.smartTriage.smartTriage_server.module.fasttrack.entity.FastTrackActivation;
import com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level evidence: a fast-track activation is OWNED + pushed in real time
 * (the prior save-only/wrong-type defect), MI auto-orders an ECG, the thrombolysis
 * window assessment is advisory + correctly tiered, ST elevation upgrades to STEMI,
 * and a duplicate SAME-family activation is blocked while a distinct family is allowed.
 */
class FastTrackServiceTest {

    private FastTrackActivationRepository ftRepo;
    private VisitRepository visitRepo;
    private ClinicalAlertRepository alertRepo;
    private RealTimeEventPublisher publisher;
    private com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService shiftService;
    private FastTrackService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private Visit visit;

    @BeforeEach
    void setUp() {
        ftRepo = mock(FastTrackActivationRepository.class);
        visitRepo = mock(VisitRepository.class);
        alertRepo = mock(ClinicalAlertRepository.class);
        publisher = mock(RealTimeEventPublisher.class);
        shiftService = mock(com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService.class);
        TriageRecordRepository triageRepo = mock(TriageRecordRepository.class);
        service = new FastTrackService(ftRepo, visitRepo, alertRepo, publisher, shiftService,
                triageRepo, new StrokeMIDetectionEngine());

        Hospital h = new Hospital();
        h.setId(hospitalId);
        visit = new Visit();
        visit.setId(visitId);
        visit.setVisitNumber("V-1");
        visit.setHospital(h);
        visit.setCurrentEdZone(EdZone.ACUTE);
        visit.setArrivalTime(Instant.now().minus(20, ChronoUnit.MINUTES));
        visit.setPatient(Patient.builder().firstName("Jane").lastName("Doe").build());

        when(visitRepo.findByIdAndIsActiveTrue(visitId)).thenReturn(Optional.of(visit));
        when(ftRepo.findByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(visitId)).thenReturn(List.of());
        when(ftRepo.save(any(FastTrackActivation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alertRepo.save(any(ClinicalAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shiftService.getDoctorsForZone(any(), any())).thenReturn(List.of());
        when(shiftService.getChargeNurse(any())).thenReturn(List.of());
    }

    private FastTrackActivationRequest req(FastTrackType type) {
        return FastTrackActivationRequest.builder()
                .visitId(visitId).fastTrackType(type).activatedByName("Dr X").build();
    }

    private FastTrackActivation existing(FastTrackActivation a) {
        a.setId(UUID.randomUUID());
        when(ftRepo.findByIdAndIsActiveTrue(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    @DisplayName("Activation raises an OWNED, real-time FAST_TRACK_ACTIVATED alert (not save-only VITAL_SIGN_ABNORMAL)")
    void activationAlertIsOwnedAndPushed() {
        service.activateFastTrack(req(FastTrackType.STROKE_SUSPECTED));
        ArgumentCaptor<ClinicalAlert> cap = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(alertRepo).save(cap.capture());
        assertEquals(AlertType.FAST_TRACK_ACTIVATED, cap.getValue().getAlertType());
        verify(publisher, atLeastOnce()).publishHospitalAlert(eq(hospitalId), any());
    }

    @Test
    @DisplayName("MI activation auto-orders an ECG (status ECG_ORDERED, ecgOrderedAt set)")
    void miAutoOrdersEcg() {
        FastTrackActivation a = service.activateFastTrack(req(FastTrackType.NSTEMI_SUSPECTED));
        assertEquals(FastTrackStatus.ECG_ORDERED, a.getStatus());
        assertNotNull(a.getEcgOrderedAt());
    }

    @Test
    @DisplayName("CT within 4.5h window → thrombolysis advisory flags in-window + the contraindication caveat")
    void ctWithinWindowIsAdvisoryEligible() {
        FastTrackActivation a = existing(FastTrackActivation.builder()
                .visit(visit).fastTrackType(FastTrackType.STROKE_SUSPECTED).status(FastTrackStatus.ACTIVATED)
                .activatedAt(Instant.now()).symptomOnsetTime(Instant.now().minus(60, ChronoUnit.MINUTES)).build());
        service.recordCt(a.getId(), CtResultRequest.builder().ctResult("No acute findings").isHemorrhagic(false).build());
        assertEquals(Boolean.TRUE, a.getThrombolysisEligible());
        assertTrue(a.getThrombolysisAdvisory().contains("ADVISORY"));
    }

    @Test
    @DisplayName("CT past 4.5h → not in window, advisory says OUTSIDE")
    void ctOutsideWindow() {
        FastTrackActivation a = existing(FastTrackActivation.builder()
                .visit(visit).fastTrackType(FastTrackType.STROKE_SUSPECTED).status(FastTrackStatus.ACTIVATED)
                .activatedAt(Instant.now()).symptomOnsetTime(Instant.now().minus(300, ChronoUnit.MINUTES)).build());
        service.recordCt(a.getId(), CtResultRequest.builder().ctResult("No acute findings").isHemorrhagic(false).build());
        assertEquals(Boolean.FALSE, a.getThrombolysisEligible());
        assertTrue(a.getThrombolysisAdvisory().contains("OUTSIDE"));
    }

    @Test
    @DisplayName("Hemorrhagic CT → thrombolysis CONTRAINDICATED")
    void ctHemorrhagicContraindicated() {
        FastTrackActivation a = existing(FastTrackActivation.builder()
                .visit(visit).fastTrackType(FastTrackType.STROKE_SUSPECTED).status(FastTrackStatus.ACTIVATED)
                .activatedAt(Instant.now()).symptomOnsetTime(Instant.now().minus(60, ChronoUnit.MINUTES)).build());
        service.recordCt(a.getId(), CtResultRequest.builder().ctResult("Intracerebral bleed").isHemorrhagic(true).build());
        assertEquals(Boolean.FALSE, a.getThrombolysisEligible());
        assertTrue(a.getThrombolysisAdvisory().contains("CONTRAINDICATED"));
    }

    @Test
    @DisplayName("ECG ST elevation upgrades an NSTEMI suspicion to STEMI")
    void ecgStElevationUpgradesToStemi() {
        FastTrackActivation a = existing(FastTrackActivation.builder()
                .visit(visit).fastTrackType(FastTrackType.NSTEMI_SUSPECTED).status(FastTrackStatus.ECG_ORDERED)
                .activatedAt(Instant.now()).build());
        service.recordEcg(a.getId(), EcgResultRequest.builder().ecgResult("Anterior ST elevation").stElevation(true).build());
        assertEquals(FastTrackType.STEMI_SUSPECTED, a.getFastTrackType());
    }

    @Test
    @DisplayName("Duplicate SAME-family activation is blocked; a distinct family is allowed")
    void duplicateFamilyBlockedDistinctAllowed() {
        FastTrackActivation activeStroke = FastTrackActivation.builder()
                .visit(visit).fastTrackType(FastTrackType.STROKE_SUSPECTED).status(FastTrackStatus.ACTIVATED)
                .activatedAt(Instant.now()).build();
        when(ftRepo.findByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(visitId)).thenReturn(List.of(activeStroke));

        // Same family (TIA) → blocked.
        assertThrows(ClinicalBusinessException.class,
                () -> service.activateFastTrack(req(FastTrackType.TIA_SUSPECTED)));
        // Distinct family (MI) → allowed.
        FastTrackActivation mi = service.activateFastTrack(req(FastTrackType.STEMI_SUSPECTED));
        assertEquals(FastTrackType.STEMI_SUSPECTED, mi.getFastTrackType());
    }

    @Test
    @DisplayName("Complete sets COMPLETED status + completion timestamp")
    void completeSetsStatus() {
        FastTrackActivation a = existing(FastTrackActivation.builder()
                .visit(visit).fastTrackType(FastTrackType.STROKE_SUSPECTED).status(FastTrackStatus.CT_COMPLETED)
                .activatedAt(Instant.now()).build());
        FastTrackActivation done = service.complete(a.getId(), "Thrombolysed, admitted to stroke unit");
        assertEquals(FastTrackStatus.COMPLETED, done.getStatus());
        assertNotNull(done.getCompletedAt());
        assertEquals("Thrombolysed, admitted to stroke unit", done.getOutcome());
    }

    @Test
    @DisplayName("ECG recorded on a STROKE activation does NOT re-classify it to STEMI")
    void ecgOnStrokeDoesNotUpgrade() {
        FastTrackActivation a = existing(FastTrackActivation.builder()
                .visit(visit).fastTrackType(FastTrackType.STROKE_SUSPECTED).status(FastTrackStatus.ACTIVATED)
                .activatedAt(Instant.now()).build());
        service.recordEcg(a.getId(), EcgResultRequest.builder().ecgResult("incidental").stElevation(true).build());
        assertEquals(FastTrackType.STROKE_SUSPECTED, a.getFastTrackType());
    }

    @Test
    @DisplayName("Hemorrhagic CT on a TIA activation also flags CONTRAINDICATED (stroke-family gating)")
    void tiaHemorrhagicAdvisory() {
        FastTrackActivation a = existing(FastTrackActivation.builder()
                .visit(visit).fastTrackType(FastTrackType.TIA_SUSPECTED).status(FastTrackStatus.ACTIVATED)
                .activatedAt(Instant.now()).symptomOnsetTime(Instant.now().minus(60, ChronoUnit.MINUTES)).build());
        service.recordCt(a.getId(), CtResultRequest.builder().ctResult("bleed").isHemorrhagic(true).build());
        assertEquals(Boolean.FALSE, a.getThrombolysisEligible());
        assertTrue(a.getThrombolysisAdvisory().contains("CONTRAINDICATED"));
    }
}
