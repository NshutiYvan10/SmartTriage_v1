package com.smartTriage.smartTriage_server.module.sepsis.service;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.sepsis.entity.SepsisScreening;
import com.smartTriage.smartTriage_server.module.sepsis.repository.SepsisScreeningRepository;
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
 * Evidence that the bundle-compliance escalation is no longer defeated by the
 * original SEPSIS_SCREENING alert: the "not started" escalation now uses (and
 * dedups on) the DISTINCT SEPSIS_BUNDLE_NOT_STARTED type, so it fires for an
 * unacted-on patient even while the original detection alert sits unacknowledged.
 */
class SepsisBundleMonitorServiceTest {

    private SepsisScreeningRepository sepsisRepo;
    private ClinicalAlertRepository alertRepo;
    private ShiftAssignmentService shiftService;
    private SepsisBundleMonitorService monitor;

    private final UUID visitId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sepsisRepo = mock(SepsisScreeningRepository.class);
        alertRepo = mock(ClinicalAlertRepository.class);
        shiftService = mock(ShiftAssignmentService.class);
        monitor = new SepsisBundleMonitorService(sepsisRepo, alertRepo, shiftService,
                mock(RealTimeEventPublisher.class));
        when(sepsisRepo.findActiveBundlesInProgress()).thenReturn(List.of());
        when(alertRepo.save(any(ClinicalAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shiftService.getDoctorsForZone(any(), any())).thenReturn(List.of());
        when(shiftService.getChargeNurse(any())).thenReturn(List.of());
    }

    private SepsisScreening sepsisScreenedMinutesAgo(long minutes) {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        Visit v = new Visit();
        v.setId(visitId);
        v.setVisitNumber("V-1");
        v.setHospital(h);
        v.setCurrentEdZone(EdZone.ACUTE);
        v.setPatient(Patient.builder().firstName("Jane").lastName("Doe").build());
        return SepsisScreening.builder()
                .visit(v)
                .screenedAt(Instant.now().minus(minutes, ChronoUnit.MINUTES))
                .sepsisStatus(SepsisStatus.SEPSIS_SUSPECTED)
                .build();
    }

    @Test
    @DisplayName("Bundle not started after 15 min → fires a SEPSIS_BUNDLE_NOT_STARTED escalation")
    void firesNotStartedEscalationWithDistinctType() {
        when(sepsisRepo.findSepsisWithoutBundle(anyList()))
                .thenReturn(List.of(sepsisScreenedMinutesAgo(20)));
        // Dedup keys on the DISTINCT type — an unacknowledged SEPSIS_SCREENING no longer suppresses it.
        when(alertRepo.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.SEPSIS_BUNDLE_NOT_STARTED)).thenReturn(false);

        monitor.checkBundleCompliance();

        ArgumentCaptor<ClinicalAlert> captor = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(alertRepo).save(captor.capture());
        assertEquals(AlertType.SEPSIS_BUNDLE_NOT_STARTED, captor.getValue().getAlertType());
        // The dedup must consult the distinct escalation type (NOT SEPSIS_SCREENING).
        verify(alertRepo).existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.SEPSIS_BUNDLE_NOT_STARTED);
    }

    @Test
    @DisplayName("Within the 15-min window → no escalation yet")
    void noEscalationWithinWindow() {
        when(sepsisRepo.findSepsisWithoutBundle(anyList()))
                .thenReturn(List.of(sepsisScreenedMinutesAgo(5)));
        monitor.checkBundleCompliance();
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Existing SEPSIS_BUNDLE_NOT_STARTED alert → deduped, no duplicate")
    void dedupsOnExistingEscalation() {
        when(sepsisRepo.findSepsisWithoutBundle(anyList()))
                .thenReturn(List.of(sepsisScreenedMinutesAgo(20)));
        when(alertRepo.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                eq(visitId), eq(AlertType.SEPSIS_BUNDLE_NOT_STARTED))).thenReturn(true);

        monitor.checkBundleCompliance();
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }
}
