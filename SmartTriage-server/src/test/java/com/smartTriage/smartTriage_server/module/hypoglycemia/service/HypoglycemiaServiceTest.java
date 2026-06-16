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
        service = new HypoglycemiaService(eventRepo, mock(VisitRepository.class),
                mock(TriageRecordRepository.class), alertRepo, new HypoglycemiaEnforcementEngine(),
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
}
