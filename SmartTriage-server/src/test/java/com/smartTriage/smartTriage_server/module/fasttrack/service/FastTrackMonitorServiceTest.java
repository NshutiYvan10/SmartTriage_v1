package com.smartTriage.smartTriage_server.module.fasttrack.service;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.FastTrackStatus;
import com.smartTriage.smartTriage_server.common.enums.FastTrackType;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.fasttrack.entity.FastTrackActivation;
import com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Evidence the fast-track door-to-target SLA breaches now raise real, owned
 * escalations (not just log.warn), deduped on the DISTINCT FAST_TRACK_SLA_BREACH
 * type.
 */
class FastTrackMonitorServiceTest {

    private FastTrackActivationRepository ftRepo;
    private ClinicalAlertRepository alertRepo;
    private ShiftAssignmentService shiftService;
    private FastTrackMonitorService monitor;

    private final UUID visitId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ftRepo = mock(FastTrackActivationRepository.class);
        alertRepo = mock(ClinicalAlertRepository.class);
        shiftService = mock(ShiftAssignmentService.class);
        monitor = new FastTrackMonitorService(ftRepo, alertRepo, shiftService, mock(RealTimeEventPublisher.class));
        when(alertRepo.save(any(ClinicalAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shiftService.getDoctorsForZone(any(), any())).thenReturn(List.of());
        when(shiftService.getChargeNurse(any())).thenReturn(List.of());
    }

    private FastTrackActivation strokeArrivedMinsAgo(long mins) {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        Visit v = new Visit();
        v.setId(visitId);
        v.setVisitNumber("V-1");
        v.setHospital(h);
        v.setCurrentEdZone(EdZone.ACUTE);
        v.setArrivalTime(Instant.now().minus(mins, ChronoUnit.MINUTES));
        v.setPatient(Patient.builder().firstName("Jane").lastName("Doe").build());
        return FastTrackActivation.builder()
                .visit(v).fastTrackType(FastTrackType.STROKE_SUSPECTED)
                .status(FastTrackStatus.ACTIVATED).activatedAt(Instant.now().minus(mins, ChronoUnit.MINUTES))
                .build();
    }

    @Test
    @DisplayName("Stroke, CT not done 30 min after arrival → FAST_TRACK_SLA_BREACH raised")
    void firesCtBreach() {
        when(ftRepo.findByStatusNotInAndIsActiveTrue(anyList()))
                .thenReturn(List.of(strokeArrivedMinsAgo(30)));
        when(alertRepo.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.FAST_TRACK_SLA_BREACH)).thenReturn(false);

        monitor.checkSlaBreaches();

        ArgumentCaptor<ClinicalAlert> cap = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(alertRepo).save(cap.capture());
        assertEquals(AlertType.FAST_TRACK_SLA_BREACH, cap.getValue().getAlertType());
    }

    @Test
    @DisplayName("Within targets (5 min since arrival) → no escalation")
    void noBreachWithinWindow() {
        when(ftRepo.findByStatusNotInAndIsActiveTrue(anyList()))
                .thenReturn(List.of(strokeArrivedMinsAgo(5)));
        monitor.checkSlaBreaches();
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Existing unacknowledged breach → deduped, no duplicate")
    void dedupsOnExistingBreach() {
        when(ftRepo.findByStatusNotInAndIsActiveTrue(anyList()))
                .thenReturn(List.of(strokeArrivedMinsAgo(30)));
        when(alertRepo.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                eq(visitId), eq(AlertType.FAST_TRACK_SLA_BREACH))).thenReturn(true);
        monitor.checkSlaBreaches();
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Hemorrhagic stroke (CT done) → NO door-to-treatment breach (thrombolysis contraindicated)")
    void hemorrhagicNoTreatmentBreach() {
        FastTrackActivation a = strokeArrivedMinsAgo(90);
        a.setCtCompletedAt(Instant.now().minus(60, ChronoUnit.MINUTES)); // CT done → no CT breach
        a.setIsHemorrhagic(true);
        when(ftRepo.findByStatusNotInAndIsActiveTrue(anyList())).thenReturn(List.of(a));
        when(alertRepo.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.FAST_TRACK_SLA_BREACH)).thenReturn(false);
        monitor.checkSlaBreaches();
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Patient discharged (visit terminal) → NO escalation even if the activation was left open")
    void terminalVisitNoBreach() {
        FastTrackActivation a = strokeArrivedMinsAgo(30);
        a.getVisit().setStatus(VisitStatus.DISCHARGED);
        when(ftRepo.findByStatusNotInAndIsActiveTrue(anyList())).thenReturn(List.of(a));
        monitor.checkSlaBreaches();
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }
}
