package com.smartTriage.smartTriage_server.module.hypoglycemia.service;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Evidence the mandatory 15-minute recheck is enforced: an unresolved event whose
 * recheckDueAt has lapsed raises an owned HYPOGLYCEMIA_RECHECK_OVERDUE escalation
 * (deduped), and a discharged patient / not-yet-due event does not.
 */
class HypoglycemiaRecheckMonitorServiceTest {

    private HypoglycemiaEventRepository eventRepo;
    private ClinicalAlertRepository alertRepo;
    private ShiftAssignmentService shiftService;
    private HypoglycemiaRecheckMonitorService monitor;

    private final UUID visitId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        eventRepo = mock(HypoglycemiaEventRepository.class);
        alertRepo = mock(ClinicalAlertRepository.class);
        shiftService = mock(ShiftAssignmentService.class);
        monitor = new HypoglycemiaRecheckMonitorService(eventRepo, alertRepo, shiftService,
                mock(RealTimeEventPublisher.class));
        when(alertRepo.save(any(ClinicalAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shiftService.getDoctorsForZone(any(), any())).thenReturn(List.of());
        when(shiftService.getChargeNurse(any())).thenReturn(List.of());
    }

    private HypoglycemiaEvent eventRecheckDue(long minutesFromNow, VisitStatus status) {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        Visit v = new Visit();
        v.setId(visitId);
        v.setVisitNumber("V-1");
        v.setHospital(h);
        v.setCurrentEdZone(EdZone.ACUTE);
        v.setStatus(status);
        v.setPatient(Patient.builder().firstName("Jane").lastName("Doe").build());
        return HypoglycemiaEvent.builder()
                .visit(v).detectedAt(Instant.now().minus(30, ChronoUnit.MINUTES))
                .severity("SEVERE").triggerReason("x")
                .recheckDueAt(Instant.now().plus(minutesFromNow, ChronoUnit.MINUTES))
                .build();
    }

    @Test
    @DisplayName("Recheck overdue (due 5 min ago) → HYPOGLYCEMIA_RECHECK_OVERDUE raised")
    void overdueRaisesEscalation() {
        when(eventRepo.findByResolvedFalseAndIsActiveTrue())
                .thenReturn(List.of(eventRecheckDue(-5, VisitStatus.UNDER_TREATMENT)));
        when(alertRepo.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.HYPOGLYCEMIA_RECHECK_OVERDUE)).thenReturn(false);

        monitor.checkRecheckOverdue();

        ArgumentCaptor<ClinicalAlert> cap = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(alertRepo).save(cap.capture());
        assertEquals(AlertType.HYPOGLYCEMIA_RECHECK_OVERDUE, cap.getValue().getAlertType());
    }

    @Test
    @DisplayName("Recheck not yet due → no escalation")
    void notYetDue() {
        when(eventRepo.findByResolvedFalseAndIsActiveTrue())
                .thenReturn(List.of(eventRecheckDue(10, VisitStatus.UNDER_TREATMENT)));
        monitor.checkRecheckOverdue();
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Existing unacknowledged overdue alert → deduped")
    void dedupes() {
        when(eventRepo.findByResolvedFalseAndIsActiveTrue())
                .thenReturn(List.of(eventRecheckDue(-5, VisitStatus.UNDER_TREATMENT)));
        when(alertRepo.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                eq(visitId), eq(AlertType.HYPOGLYCEMIA_RECHECK_OVERDUE))).thenReturn(true);
        monitor.checkRecheckOverdue();
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Discharged patient (terminal visit) → no escalation even if recheck overdue")
    void terminalVisitSkipped() {
        when(eventRepo.findByResolvedFalseAndIsActiveTrue())
                .thenReturn(List.of(eventRecheckDue(-30, VisitStatus.DISCHARGED)));
        monitor.checkRecheckOverdue();
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }
}
