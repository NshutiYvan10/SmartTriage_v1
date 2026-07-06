package com.smartTriage.smartTriage_server.module.hypoglycemia.service;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.GlucoseUnit;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.RepeatGlucoseRequest;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.HypoglycemiaEnforcementEngine;
import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service evidence: a low glucose reading auto-creates an OWNED, real-time
 * HYPOGLYCEMIA_CRITICAL event/alert; a normal reading does not; a persistently-low
 * recheck escalates (not just logs); a recovered recheck resolves and stops the clock.
 */
class HypoglycemiaServiceTest {

    private HypoglycemiaEventRepository eventRepo;
    private ClinicalAlertRepository alertRepo;
    private RealTimeEventPublisher publisher;
    private ShiftAssignmentService shiftService;
    private VisitRepository visitRepo;
    private TriageRecordRepository triageRepo;
    private HypoglycemiaService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private Visit visit;

    @BeforeEach
    void setUp() {
        eventRepo = mock(HypoglycemiaEventRepository.class);
        alertRepo = mock(ClinicalAlertRepository.class);
        publisher = mock(RealTimeEventPublisher.class);
        shiftService = mock(ShiftAssignmentService.class);
        visitRepo = mock(VisitRepository.class);
        triageRepo = mock(TriageRecordRepository.class);
        service = new HypoglycemiaService(eventRepo, visitRepo,
                triageRepo, alertRepo, new HypoglycemiaEnforcementEngine(),
                publisher, shiftService);

        Hospital h = new Hospital();
        h.setId(hospitalId);
        visit = new Visit();
        visit.setId(visitId);
        visit.setVisitNumber("V-1");
        visit.setHospital(h);
        visit.setCurrentEdZone(EdZone.ACUTE);
        visit.setPatient(Patient.builder().firstName("Jane").lastName("Doe")
                .dateOfBirth(java.time.LocalDate.now().minusYears(45)).build());

        when(eventRepo.save(any(HypoglycemiaEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alertRepo.save(any(ClinicalAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shiftService.getDoctorsForZone(any(), any())).thenReturn(java.util.List.of());
        when(shiftService.getChargeNurse(any())).thenReturn(java.util.List.of());
    }

    @Test
    @DisplayName("A severe low reading auto-creates an OWNED, real-time HYPOGLYCEMIA_CRITICAL event + alert with a recheck due")
    void severeReadingCreatesOwnedAlert() {
        when(eventRepo.existsByVisitIdAndResolvedFalseAndIsActiveTrue(visitId)).thenReturn(false);

        service.evaluateGlucoseReading(visit, 1.8, false, "MANUAL_VITALS");

        ArgumentCaptor<HypoglycemiaEvent> evCap = ArgumentCaptor.forClass(HypoglycemiaEvent.class);
        verify(eventRepo).save(evCap.capture());
        assertEquals("SEVERE", evCap.getValue().getSeverity());
        assertEquals("MANUAL_VITALS", evCap.getValue().getGlucoseSource());
        assertNotNull(evCap.getValue().getRecheckDueAt());

        ArgumentCaptor<ClinicalAlert> alCap = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(alertRepo).save(alCap.capture());
        assertEquals(AlertType.HYPOGLYCEMIA_CRITICAL, alCap.getValue().getAlertType());
        verify(publisher, atLeastOnce()).publishHospitalAlert(eq(hospitalId), any());
    }

    @Test
    @DisplayName("A normal reading creates NO event and NO alert")
    void normalReadingNoEvent() {
        service.evaluateGlucoseReading(visit, 5.5, false, "MANUAL_VITALS");
        verify(eventRepo, never()).save(any(HypoglycemiaEvent.class));
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Repeat glucose still low (2.5) → NOT resolved, raises an escalation alert, re-arms the recheck clock")
    void repeatStillLowEscalates() {
        HypoglycemiaEvent event = HypoglycemiaEvent.builder()
                .visit(visit).detectedAt(Instant.now()).glucoseLevel(2.0).triggerReason("x")
                .severity("SEVERE").neonatal(false).build();
        event.setId(UUID.randomUUID());
        when(eventRepo.findByIdAndIsActiveTrue(event.getId())).thenReturn(Optional.of(event));

        service.recordRepeatGlucose(event.getId(), RepeatGlucoseRequest.builder().glucoseLevel(2.5).build());

        assertFalse(event.isResolved());
        assertNotNull(event.getRecheckDueAt());
        verify(alertRepo).save(any(ClinicalAlert.class)); // the persistent-hypoglycemia escalation
    }

    @Test
    @DisplayName("Repeat glucose recovered (5.0) → resolved, recheck clock cleared, no new alert")
    void repeatRecoveredResolves() {
        HypoglycemiaEvent event = HypoglycemiaEvent.builder()
                .visit(visit).detectedAt(Instant.now()).glucoseLevel(2.0).triggerReason("x")
                .severity("SEVERE").neonatal(false).build();
        event.setId(UUID.randomUUID());
        when(eventRepo.findByIdAndIsActiveTrue(event.getId())).thenReturn(Optional.of(event));

        service.recordRepeatGlucose(event.getId(), RepeatGlucoseRequest.builder().glucoseLevel(5.0).build());

        assertTrue(event.isResolved());
        assertNull(event.getRecheckDueAt());
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Repeat glucose in mg/dL still-low (36 mg/dL = 2.0 mmol/L) → converted, NOT resolved, escalates")
    void repeatMgDlStillLowConvertsAndEscalates() {
        HypoglycemiaEvent event = openEvent();
        when(eventRepo.findByIdAndIsActiveTrue(event.getId())).thenReturn(Optional.of(event));

        service.recordRepeatGlucose(event.getId(),
                RepeatGlucoseRequest.builder().glucoseLevel(36.0).unit(GlucoseUnit.MG_DL).build());

        assertFalse(event.isResolved());
        assertEquals(2.0, event.getRepeatGlucoseLevel(), 0.001); // stored in mmol/L
        verify(alertRepo).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Repeat glucose in mg/dL recovered (90 mg/dL = 5.0 mmol/L) → converted and resolved")
    void repeatMgDlRecoveredConvertsAndResolves() {
        HypoglycemiaEvent event = openEvent();
        when(eventRepo.findByIdAndIsActiveTrue(event.getId())).thenReturn(Optional.of(event));

        service.recordRepeatGlucose(event.getId(),
                RepeatGlucoseRequest.builder().glucoseLevel(90.0).unit(GlucoseUnit.MG_DL).build());

        assertTrue(event.isResolved());
        assertEquals(5.0, event.getRepeatGlucoseLevel(), 0.001);
    }

    @Test
    @DisplayName("Repeat that classifies NORMAL but is implausibly high (20 mmol/L) is NOT auto-resolved — kept open for explicit resolve")
    void repeatImplausiblyHighNotAutoResolved() {
        HypoglycemiaEvent event = openEvent();
        when(eventRepo.findByIdAndIsActiveTrue(event.getId())).thenReturn(Optional.of(event));

        service.recordRepeatGlucose(event.getId(), RepeatGlucoseRequest.builder().glucoseLevel(20.0).build());

        assertFalse(event.isResolved(), "a suspiciously-high repeat must not silently resolve a critical event");
        assertNotNull(event.getRecheckDueAt(), "the recheck clock stays armed");
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Repeat outside the physiologic window (90 mmol/L) is rejected as a unit/data error")
    void repeatOutsidePhysiologicWindowRejected() {
        HypoglycemiaEvent event = openEvent();
        when(eventRepo.findByIdAndIsActiveTrue(event.getId())).thenReturn(Optional.of(event));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.recordRepeatGlucose(event.getId(), RepeatGlucoseRequest.builder().glucoseLevel(90.0).build()));
        assertFalse(event.isResolved());
    }

    private HypoglycemiaEvent openEvent() {
        HypoglycemiaEvent event = HypoglycemiaEvent.builder()
                .visit(visit).detectedAt(Instant.now()).glucoseLevel(2.0).triggerReason("x")
                .severity("SEVERE").neonatal(false).build();
        event.setId(UUID.randomUUID());
        return event;
    }

    // ────────────────────────────────────────────────────────────────────
    // Front-door enforcement (the gate-order fix + the triage hook)
    // ────────────────────────────────────────────────────────────────────

    private com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord triageWith(
            Double glucose, boolean coma) {
        var t = new com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord();
        t.setBloodGlucose(glucose);
        t.setHasComa(coma);
        return t;
    }

    @Test
    @DisplayName("GATE FIX: a severe-low triage glucose files the event even when NO check was 'required' "
            + "(non-diabetic, alert patient) — the old gate returned early and filed NOTHING")
    void checkAndEnforce_hypoglycemicValueAlwaysFilesEvent() {
        when(visitRepo.findByIdAndIsActiveTrue(visitId)).thenReturn(Optional.of(visit));
        when(triageRepo.findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId))
                .thenReturn(Optional.of(triageWith(2.1, false))); // no triggers, not diabetic
        when(eventRepo.existsByVisitIdAndResolvedFalseAndIsActiveTrue(visitId)).thenReturn(false);

        var response = service.checkAndEnforce(visitId);

        assertEquals("SEVERE", response.getSeverity());
        ArgumentCaptor<HypoglycemiaEvent> cap = ArgumentCaptor.forClass(HypoglycemiaEvent.class);
        verify(eventRepo).save(cap.capture());
        assertEquals("TRIAGE", cap.getValue().getGlucoseSource());
        verify(alertRepo).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("TRIAGE HOOK: enforceFromTriage files the event + alert for a hypoglycemic triage glucose")
    void enforceFromTriage_lowGlucoseFilesEvent() {
        when(eventRepo.existsByVisitIdAndResolvedFalseAndIsActiveTrue(visitId)).thenReturn(false);

        service.enforceFromTriage(visit, triageWith(2.1, false));

        ArgumentCaptor<HypoglycemiaEvent> cap = ArgumentCaptor.forClass(HypoglycemiaEvent.class);
        verify(eventRepo).save(cap.capture());
        assertEquals("TRIAGE", cap.getValue().getGlucoseSource());
        assertNotNull(cap.getValue().getRecheckDueAt(), "mandatory recheck clock armed at the front door");
        verify(alertRepo).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("TRIAGE HOOK: coma WITHOUT a glucose pages GLUCOSE CHECK REQUIRED; an open alert dedupes a re-page")
    void enforceFromTriage_mandatoryCheckWithoutGlucoseAlertsOnce() {
        // First triage: no open hypoglycemia alert → the check-required alert fires.
        when(alertRepo.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.HYPOGLYCEMIA_CRITICAL)).thenReturn(false);
        service.enforceFromTriage(visit, triageWith(null, true));
        ArgumentCaptor<ClinicalAlert> cap = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(alertRepo).save(cap.capture());
        assertTrue(cap.getValue().getTitle().contains("GLUCOSE CHECK REQUIRED"));
        verify(eventRepo, never()).save(any(HypoglycemiaEvent.class)); // no value → no event

        // Re-triage while that alert is still open → deduped, no second page.
        when(alertRepo.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.HYPOGLYCEMIA_CRITICAL)).thenReturn(true);
        service.enforceFromTriage(visit, triageWith(null, true));
        verify(alertRepo, org.mockito.Mockito.times(1)).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("PLAUSIBILITY: an implausible auto reading (0.05 or 999 mmol/L) files NOTHING — data error, not a page")
    void autoReadingOutsidePhysiologicWindowIgnored() {
        service.evaluateGlucoseReading(visit, 0.05, false, "IOT_STREAM");
        service.evaluateGlucoseReading(visit, 999.0, false, "LAB");
        verify(eventRepo, never()).save(any(HypoglycemiaEvent.class));
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }
}
