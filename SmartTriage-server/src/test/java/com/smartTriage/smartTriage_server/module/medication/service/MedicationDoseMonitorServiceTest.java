package com.smartTriage.smartTriage_server.module.medication.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.DoseKind;
import com.smartTriage.smartTriage_server.common.enums.DoseStatus;
import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the V67 dose monitor: overdue re-notification fires
 * once at the grace window, missed escalation transitions the dose +
 * CRITICAL-alerts the charge nurse + rolls the schedule forward (one
 * missed dose never kills the course), and ended orders complete.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MedicationDoseMonitorServiceTest {

    @Mock private MedicationDoseRepository doseRepository;
    @Mock private MedicationAdministrationRepository medicationRepository;
    @Mock private ClinicalAlertRepository clinicalAlertRepository;
    @Mock private RealTimeEventPublisher realTimeEventPublisher;
    @Mock private MedicationScheduleService scheduleService;

    @InjectMocks private MedicationDoseMonitorService monitor;

    private Visit visit;
    private MedicationAdministration order;
    private MedicationDose dose;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(monitor, "overdueGraceMinutes", 15);
        ReflectionTestUtils.setField(monitor, "missedThresholdMinutes", 60);

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setHospital(hospital);
        visit = new Visit();
        visit.setId(UUID.randomUUID());
        visit.setVisitNumber("V-100");
        visit.setPatient(patient);
        visit.setHospital(hospital);

        order = MedicationAdministration.builder()
                .visit(visit)
                .drugName("Ceftriaxone")
                .route(MedicationRoute.IV)
                .prescribedAt(Instant.now().minus(Duration.ofHours(3)))
                .status(MedicationStatus.PRESCRIBED)
                .prescriptionType(PrescriptionType.SCHEDULED)
                .build();
        order.setId(UUID.randomUUID());

        dose = MedicationDose.builder()
                .medication(order)
                .visit(visit)
                .kind(DoseKind.SCHEDULED_DOSE)
                .status(DoseStatus.DUE)
                .sequenceNumber(2)
                .build();
        dose.setId(UUID.randomUUID());

        // The repo "query" mirrors the real semantics: DUE + dueAt < cutoff.
        when(doseRepository.findDueBefore(any())).thenAnswer(inv -> {
            Instant cutoff = inv.getArgument(0);
            return dose.getStatus() == DoseStatus.DUE
                    && dose.getDueAt() != null
                    && dose.getDueAt().isBefore(cutoff)
                    ? List.of(dose) : List.of();
        });
        when(doseRepository.save(any(MedicationDose.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(medicationRepository.findLiveTypedOrdersPastEnd(any())).thenReturn(List.of());
        when(clinicalAlertRepository
                .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(any(), any()))
                .thenReturn(false);
        when(scheduleService.doseEventPayload(any(), anyString()))
                .thenReturn(new HashMap<>());
    }

    @Test
    void overdueDose_isNotifiedOnce_withHighAlert() {
        dose.setDueAt(Instant.now().minus(Duration.ofMinutes(25))); // past 15-min grace, before 60-min miss

        monitor.tick();

        assertNotNull(dose.getOverdueNotifiedAt());
        assertEquals(DoseStatus.DUE, dose.getStatus()); // still administrable
        ArgumentCaptor<ClinicalAlert> alert = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(clinicalAlertRepository).save(alert.capture());
        assertEquals(AlertType.MEDICATION_DOSE_OVERDUE, alert.getValue().getAlertType());
        assertEquals(AlertSeverity.HIGH, alert.getValue().getSeverity());

        // Second tick: already notified — no duplicate alert.
        monitor.tick();
        verify(clinicalAlertRepository, times(1)).save(any(ClinicalAlert.class));
    }

    @Test
    void missedDose_escalatesCritical_andRollsScheduleForward() {
        Instant dueAt = Instant.now().minus(Duration.ofMinutes(75)); // past the 60-min threshold
        dose.setDueAt(dueAt);

        monitor.tick();

        assertEquals(DoseStatus.MISSED, dose.getStatus());
        assertNotNull(dose.getMissedEscalatedAt());
        ArgumentCaptor<ClinicalAlert> alert = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(clinicalAlertRepository).save(alert.capture());
        assertEquals(AlertType.MEDICATION_DOSE_MISSED, alert.getValue().getAlertType());
        assertEquals(AlertSeverity.CRITICAL, alert.getValue().getSeverity());
        // The course survives the miss: next dose anchored to the missed slot.
        verify(scheduleService).rollScheduleForward(order, dueAt);
    }

    @Test
    void freshDose_isLeftAlone() {
        dose.setDueAt(Instant.now().plus(Duration.ofHours(2)));

        monitor.tick();

        assertEquals(DoseStatus.DUE, dose.getStatus());
        assertNull(dose.getOverdueNotifiedAt());
        verify(clinicalAlertRepository, never()).save(any(ClinicalAlert.class));
        verify(scheduleService, never()).rollScheduleForward(any(), any());
    }

    @Test
    void ordersPastTheirEnd_areCompleted() {
        dose.setDueAt(Instant.now().plus(Duration.ofHours(2))); // dose lane quiet
        when(medicationRepository.findLiveTypedOrdersPastEnd(any()))
                .thenReturn(List.of(order));

        monitor.tick();

        verify(scheduleService).completeOrder(order, "Scheduled duration elapsed");
    }
}
