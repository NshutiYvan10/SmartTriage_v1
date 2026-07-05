package com.smartTriage.smartTriage_server.module.alert.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AlertEscalationService}'s re-escalation loops.
 *
 * <p>Covers the patient-safety fix (1a) that removed the "re-page exactly once then go
 * silent forever" dead-end: an unacknowledged time-critical / EMS pre-arrival / top-tier
 * doctor alert must keep re-paging on an interval until it is acknowledged. Real entities +
 * mocked repository/publisher.
 */
class AlertEscalationServiceTest {

    private ClinicalAlertRepository repo;
    private RealTimeEventPublisher publisher;
    private AlertEscalationService service;

    private final UUID HOSPITAL = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalAlertRepository.class);
        ShiftAssignmentService shiftAssignmentService = mock(ShiftAssignmentService.class);
        publisher = mock(RealTimeEventPublisher.class);
        service = new AlertEscalationService(repo, shiftAssignmentService, publisher);

        // Each test drives ONE pipeline; the others default to empty.
        when(repo.findUnacknowledgedDoctorNotifications()).thenReturn(List.of());
        when(repo.findUnacknowledgedTimeCriticalAlerts(any())).thenReturn(List.of());
        when(repo.findUnacknowledgedCriticalEmsPreArrivals()).thenReturn(List.of());
        when(repo.save(any(ClinicalAlert.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ClinicalAlert alert(AlertType type, AlertSeverity severity,
            Instant createdAt, Instant escalatedAt, int tier) {
        Hospital h = new Hospital();
        h.setId(HOSPITAL);
        Patient p = new Patient();
        p.setFirstName("Unknown");
        p.setLastName("Alpha");
        p.setHospital(h);
        Visit v = new Visit();
        v.setPatient(p);
        v.setVisitNumber("V-IT-1");
        ClinicalAlert a = ClinicalAlert.builder()
                .visit(v)
                .alertType(type)
                .severity(severity)
                .title("test alert")
                .message("test")
                .escalationTier(tier)
                .escalatedAt(escalatedAt)
                .build();
        a.setCreatedAt(createdAt);
        return a;
    }

    private Instant minutesAgo(int m) { return Instant.now().minus(m, ChronoUnit.MINUTES); }

    // ── EMS pre-arrival ─────────────────────────────────────────────────

    @Test
    void unackedCriticalPreArrival_pastFuse_reAlarms() {
        ClinicalAlert a = alert(AlertType.EMS_PRE_ARRIVAL, AlertSeverity.CRITICAL, minutesAgo(3), null, 1);
        when(repo.findUnacknowledgedCriticalEmsPreArrivals()).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, times(1)).publishHospitalAlert(eq(HOSPITAL), any());
        assertNotNull(a.getEscalatedAt());
        assertEquals(2, a.getEscalationTier());
    }

    @Test
    void unackedCriticalPreArrival_withinFuse_doesNotReAlarmYet() {
        ClinicalAlert a = alert(AlertType.EMS_PRE_ARRIVAL, AlertSeverity.CRITICAL, minutesAgo(1), null, 1);
        when(repo.findUnacknowledgedCriticalEmsPreArrivals()).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, never()).publishHospitalAlert(any(UUID.class), any());
        assertNull(a.getEscalatedAt());
    }

    @Test
    void unackedCriticalPreArrival_alreadyEscalated_reAlarmsAgainAfterInterval() {
        // Already paged once (escalatedAt 3 min ago). Previously the finder filtered
        // escalatedAt IS NULL so this alert would never re-fire; now it must re-page again.
        ClinicalAlert a = alert(AlertType.EMS_PRE_ARRIVAL, AlertSeverity.CRITICAL,
                minutesAgo(10), minutesAgo(3), 2);
        when(repo.findUnacknowledgedCriticalEmsPreArrivals()).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, times(1)).publishHospitalAlert(eq(HOSPITAL), any());
        assertEquals(3, a.getEscalationTier()); // bumped again → client re-alarms
    }

    // ── time-critical clinical alerts (sepsis, hypoglycemia, …) ─────────

    @Test
    void timeCritical_pastAckWindow_firstReBroadcast() {
        ClinicalAlert a = alert(AlertType.HYPOGLYCEMIA_CRITICAL, AlertSeverity.CRITICAL, minutesAgo(6), null, 1);
        when(repo.findUnacknowledgedTimeCriticalAlerts(any())).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, times(1)).publishHospitalAlert(eq(HOSPITAL), any());
        assertNotNull(a.getEscalatedAt());
        assertEquals(2, a.getEscalationTier());
        assertEquals(AlertSeverity.CRITICAL, a.getSeverity());
    }

    @Test
    void timeCritical_withinAckWindow_doesNotBroadcast() {
        ClinicalAlert a = alert(AlertType.SEPSIS_SCREENING, AlertSeverity.HIGH, minutesAgo(2), null, 1);
        when(repo.findUnacknowledgedTimeCriticalAlerts(any())).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, never()).publishHospitalAlert(any(UUID.class), any());
        assertNull(a.getEscalatedAt());
    }

    @Test
    void timeCritical_alreadyEscalated_reBroadcastsAgainAfterRepeatInterval() {
        // THE core 1a fix: previously escalatedAt != null → skip forever. Now, once the
        // repeat interval elapses since the last page, it re-broadcasts again.
        ClinicalAlert a = alert(AlertType.SEPSIS_BUNDLE_OVERDUE, AlertSeverity.CRITICAL,
                minutesAgo(30), minutesAgo(6), 2);
        when(repo.findUnacknowledgedTimeCriticalAlerts(any())).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, times(1)).publishHospitalAlert(eq(HOSPITAL), any());
        assertEquals(3, a.getEscalationTier()); // climbs each cycle so the client re-alarms
        assertTrue(a.getMessage().contains("[ESCALATED"));
    }

    @Test
    void timeCritical_alreadyEscalated_withinRepeatInterval_doesNotReBroadcast() {
        ClinicalAlert a = alert(AlertType.SEPSIS_BUNDLE_OVERDUE, AlertSeverity.CRITICAL,
                minutesAgo(30), minutesAgo(2), 2);
        when(repo.findUnacknowledgedTimeCriticalAlerts(any())).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, never()).publishHospitalAlert(any(UUID.class), any());
        assertEquals(2, a.getEscalationTier()); // unchanged — still inside the interval
    }

    // ── doctor pipeline (Tier 1→2→3, then repeating) ────────────────────

    @Test
    void doctorAlert_atTopTier_keepsRePagingAfterInterval() {
        // A DOCTOR alert that already reached Tier 3 must not go silent — it re-pages
        // all-staff + audible again after the repeat interval, incrementing the tier.
        ClinicalAlert a = alert(AlertType.DOCTOR_NOTIFICATION, AlertSeverity.CRITICAL,
                minutesAgo(20), minutesAgo(6), 3);
        when(repo.findUnacknowledgedDoctorNotifications()).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, times(1)).publishHospitalAlert(eq(HOSPITAL), any());
        assertEquals(4, a.getEscalationTier());
    }

    @Test
    void doctorAlert_atTopTier_withinInterval_doesNotRePage() {
        ClinicalAlert a = alert(AlertType.DOCTOR_NOTIFICATION, AlertSeverity.CRITICAL,
                minutesAgo(20), minutesAgo(2), 3);
        when(repo.findUnacknowledgedDoctorNotifications()).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, never()).publishHospitalAlert(any(UUID.class), any());
        assertEquals(3, a.getEscalationTier());
    }
}
