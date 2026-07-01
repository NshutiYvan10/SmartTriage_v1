package com.smartTriage.smartTriage_server.module.ems.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The re-triage safety net's ESCALATION RATCHET: a field-RED patient the ED still hasn't
 * triaged doesn't just get one HIGH nudge — after the escalation window it ratchets to a
 * CRITICAL, doctor-paging alert. This is the difference between a reminder and a guarantee
 * on a life-critical path, so it's worth locking.
 */
class EmsRetriageMonitorTest {

    private VisitRepository visitRepository;
    private TriageRecordRepository triageRecordRepository;
    private ClinicalAlertRepository clinicalAlertRepository;
    private EmsRunRepository emsRunRepository;
    private RealTimeEventPublisher realTimeEventPublisher;
    private ShiftAssignmentService shiftAssignmentService;
    private EmsRetriageMonitor monitor;

    @BeforeEach
    void setUp() {
        visitRepository = mock(VisitRepository.class);
        triageRecordRepository = mock(TriageRecordRepository.class);
        clinicalAlertRepository = mock(ClinicalAlertRepository.class);
        emsRunRepository = mock(EmsRunRepository.class);
        realTimeEventPublisher = mock(RealTimeEventPublisher.class);
        shiftAssignmentService = mock(ShiftAssignmentService.class);
        when(shiftAssignmentService.getChargeNurse(any())).thenReturn(List.of());
        when(clinicalAlertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Not yet triaged in any of these tests.
        when(triageRecordRepository.findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(any()))
                .thenReturn(Optional.empty());
        monitor = new EmsRetriageMonitor(visitRepository, triageRecordRepository,
                clinicalAlertRepository, emsRunRepository, realTimeEventPublisher, shiftAssignmentService);
    }

    private Visit overdueRedVisit(long minutesPastDue) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        Visit v = new Visit();
        v.setId(UUID.randomUUID());
        v.setVisitNumber("V-KFH-1");
        v.setFieldTriageCategory("RED");
        v.setHospital(h);
        Instant now = Instant.now();
        v.setEdRetriageDueAt(now.minus(minutesPastDue, ChronoUnit.MINUTES));
        v.setArrivalConfirmedAt(now.minus(minutesPastDue + 5, ChronoUnit.MINUTES));
        return v;
    }

    @Test
    void firstNudge_raisesHighTier1_whenNoAlertYet() {
        Visit v = overdueRedVisit(1);
        when(visitRepository.findRetriageDueBefore(any())).thenReturn(List.of(v));
        when(clinicalAlertRepository.findFirstByVisitIdAndAlertTypeAndIsActiveTrueOrderByCreatedAtDesc(
                any(), eq(AlertType.FIELD_TRIAGED_AWAITING_REVIEW))).thenReturn(Optional.empty());

        monitor.checkRetriage();

        org.mockito.ArgumentCaptor<ClinicalAlert> cap = org.mockito.ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(clinicalAlertRepository).save(cap.capture());
        assertEquals(AlertSeverity.HIGH, cap.getValue().getSeverity());
        assertEquals(1, cap.getValue().getEscalationTier());
    }

    @Test
    void ratchet_escalatesToCriticalTier2_whenRedStillUntriagedPastWindow() {
        Visit v = overdueRedVisit(6); // 6 min past the 5-min RED deadline → ≈11 min post-arrival
        when(visitRepository.findRetriageDueBefore(any())).thenReturn(List.of(v));
        ClinicalAlert existing = ClinicalAlert.builder()
                .alertType(AlertType.FIELD_TRIAGED_AWAITING_REVIEW)
                .severity(AlertSeverity.HIGH)
                .escalationTier(1)
                .build();
        when(clinicalAlertRepository.findFirstByVisitIdAndAlertTypeAndIsActiveTrueOrderByCreatedAtDesc(
                any(), eq(AlertType.FIELD_TRIAGED_AWAITING_REVIEW))).thenReturn(Optional.of(existing));

        monitor.checkRetriage();

        assertEquals(AlertSeverity.CRITICAL, existing.getSeverity());
        assertEquals(2, existing.getEscalationTier());
    }

    @Test
    void ratchet_escalatesEvenWhenNudgeWasAcknowledgedWithoutTriage() {
        // The defeat scenario: charge nurse ACKS the HIGH nudge to silence it but never triages.
        Visit v = overdueRedVisit(6);
        when(visitRepository.findRetriageDueBefore(any())).thenReturn(List.of(v));
        ClinicalAlert acked = ClinicalAlert.builder()
                .alertType(AlertType.FIELD_TRIAGED_AWAITING_REVIEW)
                .severity(AlertSeverity.HIGH)
                .escalationTier(1)
                .build();
        acked.setAcknowledged(true);
        when(clinicalAlertRepository.findFirstByVisitIdAndAlertTypeAndIsActiveTrueOrderByCreatedAtDesc(
                any(), eq(AlertType.FIELD_TRIAGED_AWAITING_REVIEW))).thenReturn(Optional.of(acked));

        monitor.checkRetriage();

        // Acking must NOT defeat the guarantee: it still escalates to CRITICAL/tier-2 and is
        // forced back to unacknowledged so the audible re-alarm fires + it re-surfaces.
        assertEquals(AlertSeverity.CRITICAL, acked.getSeverity());
        assertEquals(2, acked.getEscalationTier());
        assertFalse(acked.isAcknowledged());
    }

    @Test
    void ratchet_alsoAppliesToUncategorizedArrival() {
        Visit v = overdueRedVisit(6);
        v.setFieldTriageCategory(null); // uncategorised, not RED — still time-critical
        when(visitRepository.findRetriageDueBefore(any())).thenReturn(List.of(v));
        ClinicalAlert existing = ClinicalAlert.builder()
                .alertType(AlertType.FIELD_TRIAGED_AWAITING_REVIEW)
                .severity(AlertSeverity.HIGH)
                .escalationTier(1)
                .build();
        when(clinicalAlertRepository.findFirstByVisitIdAndAlertTypeAndIsActiveTrueOrderByCreatedAtDesc(
                any(), eq(AlertType.FIELD_TRIAGED_AWAITING_REVIEW))).thenReturn(Optional.of(existing));

        monitor.checkRetriage();

        assertEquals(AlertSeverity.CRITICAL, existing.getSeverity());
        assertEquals(2, existing.getEscalationTier());
    }

    @Test
    void noReescalation_whenNotYetPastEscalationWindow() {
        Visit v = overdueRedVisit(1); // only 1 min past due — first nudge stands, no ratchet yet
        when(visitRepository.findRetriageDueBefore(any())).thenReturn(List.of(v));
        ClinicalAlert existing = ClinicalAlert.builder()
                .alertType(AlertType.FIELD_TRIAGED_AWAITING_REVIEW)
                .severity(AlertSeverity.HIGH)
                .escalationTier(1)
                .build();
        when(clinicalAlertRepository.findFirstByVisitIdAndAlertTypeAndIsActiveTrueOrderByCreatedAtDesc(
                any(), eq(AlertType.FIELD_TRIAGED_AWAITING_REVIEW))).thenReturn(Optional.of(existing));

        monitor.checkRetriage();

        // Still tier-1 HIGH, and nothing re-saved.
        assertEquals(AlertSeverity.HIGH, existing.getSeverity());
        assertEquals(1, existing.getEscalationTier());
        verify(clinicalAlertRepository, never()).save(any());
    }
}
