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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the unacknowledged-critical-pre-arrival re-escalation added to
 * {@link AlertEscalationService}: a RED/lights ambulance pre-arrival nobody
 * acknowledged must re-alarm hospital-wide (audible) on a short fuse, and only
 * once. Real entities + mocked repository/publisher.
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

        // The other two escalation pipelines are empty for these tests.
        when(repo.findUnacknowledgedDoctorNotifications()).thenReturn(List.of());
        when(repo.findUnacknowledgedTimeCriticalAlerts()).thenReturn(List.of());
        when(repo.save(any(ClinicalAlert.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ClinicalAlert criticalPreArrival(Instant createdAt) {
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
                .alertType(AlertType.EMS_PRE_ARRIVAL)
                .severity(AlertSeverity.CRITICAL)
                .title("INCOMING CRITICAL — RTA")
                .message("Inbound: RTA • field triage RED")
                .build();
        a.setCreatedAt(createdAt); // escalatedAt stays null
        return a;
    }

    @Test
    void unackedCriticalPreArrival_pastFuse_reAlarms() {
        ClinicalAlert a = criticalPreArrival(Instant.now().minus(3, ChronoUnit.MINUTES));
        when(repo.findUnescalatedCriticalEmsPreArrivals()).thenReturn(List.of(a));

        service.checkEscalations();

        // Re-published hospital-wide with the audible-alarm payload, and stamped.
        verify(publisher, times(1)).publishAlert(eq(HOSPITAL), anyMap());
        assertNotNull(a.getEscalatedAt());
        assertEquals(2, a.getEscalationTier());
    }

    @Test
    void unackedCriticalPreArrival_withinFuse_doesNotReAlarmYet() {
        ClinicalAlert a = criticalPreArrival(Instant.now().minus(1, ChronoUnit.MINUTES)); // < 2 min
        when(repo.findUnescalatedCriticalEmsPreArrivals()).thenReturn(List.of(a));

        service.checkEscalations();

        verify(publisher, never()).publishAlert(any(UUID.class), any(Map.class));
        assertNull(a.getEscalatedAt());
    }
}
