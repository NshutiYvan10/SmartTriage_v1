package com.smartTriage.smartTriage_server.module.iot.engine;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.MonitoringState;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.alert.service.AlertEscalationService;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.VitalStream;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.VitalStreamRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.iot.service.VitalStreamService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.triage.engine.PediatricTewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.engine.TewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression stress test for {@link ContinuousMonitoringEngine} after the Alert Center
 * changes. There was no direct test of the engine before; this feeds vitals through the
 * REAL {@code analyseAndRespond} detection path and proves the deterioration alert still:
 *   - FIRES on a critical reading (SpO2 &lt; 92 Rwanda-protocol override),
 *   - carries the SAME type (DETERIORATION_DETECTED) + severity (CRITICAL) + zone target,
 *   - is routed to EXACTLY the same audience as before — hospital + the patient's zone,
 *     with NO user-targeting (the Batch-2 review reverted the doctor/charge-nurse fan-out),
 *   - is NOT duplicated when one is already open (dedup guard intact),
 *   - does NOT false-fire on normal vitals,
 *   - is NOT evaluated at all when the session is not in an auto-retriage-eligible state.
 *
 * The only Alert-Center change to the engine was moving the publish from an inline
 * publishHospitalAlert+publishZoneAlert to publishOwnedAlertAfterCommit(hospital, zone, resp,
 * EMPTY userIds) — these tests pin that exact contract so any future drift is caught.
 */
class ContinuousMonitoringEngineTest {

    private final VitalStreamRepository streamRepository = mock(VitalStreamRepository.class);
    private final TriageRecordRepository triageRecordRepository = mock(TriageRecordRepository.class);
    private final ClinicalAlertRepository clinicalAlertRepository = mock(ClinicalAlertRepository.class);
    private final VisitRepository visitRepository = mock(VisitRepository.class);
    private final DeviceSessionRepository sessionRepository = mock(DeviceSessionRepository.class);
    private final VitalStreamService vitalStreamService = mock(VitalStreamService.class);
    private final TewsCalculator tewsCalculator = mock(TewsCalculator.class);
    private final PediatricTewsCalculator pediatricTewsCalculator = mock(PediatricTewsCalculator.class);
    private final RealTimeEventPublisher eventPublisher = mock(RealTimeEventPublisher.class);
    private final AlertEscalationService alertEscalationService = mock(AlertEscalationService.class);

    private final ContinuousMonitoringEngine engine = new ContinuousMonitoringEngine(
            streamRepository, triageRecordRepository, clinicalAlertRepository, visitRepository,
            sessionRepository, vitalStreamService, tewsCalculator, pediatricTewsCalculator,
            eventPublisher, alertEscalationService);

    private final UUID HOSPITAL = UUID.randomUUID();
    private final UUID VISIT = UUID.randomUUID();
    private final UUID SESSION = UUID.randomUUID();
    private final TriageCategory CATEGORY = TriageCategory.ORANGE;
    private final EdZone EXPECTED_ZONE = EdZone.fromTriageCategory(TriageCategory.ORANGE);

    private DeviceSession liveSession() {
        Hospital h = new Hospital();
        h.setId(HOSPITAL);
        Patient p = new Patient();
        p.setFirstName("Jane");
        p.setLastName("Doe");
        p.setHospital(h);
        Visit v = new Visit();
        v.setId(VISIT);
        v.setVisitNumber("V-MON-1");
        v.setPatient(p);
        v.setCurrentTriageCategory(CATEGORY);
        DeviceSession s = new DeviceSession();
        s.setId(SESSION);
        s.setVisit(v);
        s.setMonitoringState(MonitoringState.LIVE);
        return s;
    }

    private VitalStream reading(int spo2) {
        VitalStream r = new VitalStream();
        r.setSpo2(spo2);
        r.setValidated(true);
        return r;
    }

    private void commonStubs(DeviceSession session) {
        when(sessionRepository.findById(SESSION)).thenReturn(Optional.of(session));
        when(triageRecordRepository.findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(any()))
                .thenReturn(Optional.empty());
        // Skip the auto-retriage body cleanly (the deterioration ALERT runs before this).
        when(vitalStreamService.createVitalSnapshot(any(), any())).thenReturn(null);
        when(clinicalAlertRepository.save(any(ClinicalAlert.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void criticalSpo2_firesDeteriorationAlert_routedHospitalAndZone_notUserTargeted() {
        DeviceSession session = liveSession();
        commonStubs(session);
        when(streamRepository.findValidatedInTimeRange(eq(VISIT), any(), any()))
                .thenReturn(List.of(reading(85))); // SpO2 85 < 92 → Rwanda-protocol critical
        when(clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                eq(VISIT), eq(AlertType.DETERIORATION_DETECTED))).thenReturn(false);

        var result = engine.analyseAndRespond(VISIT, session);

        // Detection unchanged: critical SpO2 → deterioration detected.
        assertThat(result.deteriorationDetected()).isTrue();
        assertThat(result.pattern().name()).isEqualTo("SPO2_OVERRIDE");

        // Alert row: same type + severity, zone-tagged, NOT doctor-owned.
        ArgumentCaptor<ClinicalAlert> cap = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(clinicalAlertRepository).save(cap.capture());
        ClinicalAlert alert = cap.getValue();
        assertThat(alert.getAlertType()).isEqualTo(AlertType.DETERIORATION_DETECTED);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(alert.getTargetZone()).isEqualTo(EXPECTED_ZONE);
        assertThat(alert.getTargetDoctor()).isNull();

        // Routing contract: hospital + zone, EMPTY user list (no doctor/charge-nurse paging).
        verify(eventPublisher, times(1)).publishOwnedAlertAfterCommit(
                eq(HOSPITAL), eq(EXPECTED_ZONE), any(), argThat(Collection::isEmpty));
        // And the engine must NOT fall back to any other alert-publish channel for this.
        verify(eventPublisher, never()).publishHospitalAlert(any(), any());
        verify(eventPublisher, never()).publishUserAlert(any(), any());
    }

    @Test
    void deteriorationAlert_dedupedWhenAlreadyOpen_noDuplicate() {
        DeviceSession session = liveSession();
        commonStubs(session);
        when(streamRepository.findValidatedInTimeRange(eq(VISIT), any(), any()))
                .thenReturn(List.of(reading(85)));
        when(clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                eq(VISIT), eq(AlertType.DETERIORATION_DETECTED))).thenReturn(true); // already open

        var result = engine.analyseAndRespond(VISIT, session);

        assertThat(result.deteriorationDetected()).isTrue(); // still DETECTED...
        // ...but NO new alert created and NO new broadcast — the single open alert stands.
        verify(clinicalAlertRepository, never()).save(any());
        verify(eventPublisher, never()).publishOwnedAlertAfterCommit(any(), any(), any(), anyList());
    }

    @Test
    void normalVitals_noDeteriorationAlert() {
        DeviceSession session = liveSession();
        commonStubs(session);
        when(streamRepository.findValidatedInTimeRange(eq(VISIT), any(), any()))
                .thenReturn(List.of(reading(98))); // healthy SpO2

        var result = engine.analyseAndRespond(VISIT, session);

        assertThat(result.deteriorationDetected()).isFalse();
        verify(clinicalAlertRepository, never()).save(any());
        verify(eventPublisher, never()).publishOwnedAlertAfterCommit(any(), any(), any(), anyList());
    }

    @Test
    void nonLiveSession_skipsProcessingEntirely() {
        DeviceSession session = liveSession();
        session.setMonitoringState(MonitoringState.DISCONNECTED); // not auto-retriage-eligible
        when(sessionRepository.findById(SESSION)).thenReturn(Optional.of(session));

        var result = engine.analyseAndRespond(VISIT, session);

        assertThat(result.deteriorationDetected()).isFalse();
        verify(streamRepository, never()).findValidatedInTimeRange(any(), any(), any());
        verify(clinicalAlertRepository, never()).save(any());
        verify(eventPublisher, never()).publishOwnedAlertAfterCommit(any(), any(), any(), anyList());
    }
}
