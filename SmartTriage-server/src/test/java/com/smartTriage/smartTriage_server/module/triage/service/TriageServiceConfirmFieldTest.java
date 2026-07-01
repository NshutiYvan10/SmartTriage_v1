package com.smartTriage.smartTriage_server.module.triage.service;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.alert.service.AlertEscalationService;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link TriageService#confirmFieldTriage} — the EMS field-triage
 * confirmation shortcut (the receiving clinician accepts a RED/ORANGE ambulance arrival's field
 * category, flipping the visit to TRIAGED without re-running the full form). Exercises the clinical
 * guards and the attributed-record happy path. Authorization is tested separately in
 * {@code ClinicalAuthzTest#callerCanConfirmFieldTriage_*}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TriageServiceConfirmFieldTest {

    @Mock private VisitService visitService;
    @Mock private TriageRecordRepository triageRecordRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private EmsRunRepository emsRunRepository;
    @Mock private AlertEscalationService alertEscalationService;
    @Mock private RealTimeEventPublisher eventPublisher;

    @InjectMocks private TriageService triageService;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private Visit placedArrival(UUID visitId, VisitStatus status, TriageCategory category) {
        Patient p = new Patient();
        p.setFirstName("Unknown");
        p.setLastName("Alpha");
        Visit v = new Visit();
        v.setId(visitId);
        v.setVisitNumber("V-2026-001");
        v.setStatus(status);
        v.setCurrentTriageCategory(category);
        v.setCurrentEdZone(EdZone.RESUS);
        v.setChiefComplaint("Chest pain, diaphoretic");
        v.setEmsRunId(UUID.randomUUID());
        v.setEdRetriageDueAt(Instant.now().plusSeconds(120));
        v.setPatient(p);
        return v;
    }

    private User actor() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName("Grace");
        u.setLastName("Uwase");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null));
        return u;
    }

    @Test
    void confirmFieldTriage_happyPath_createsAttributedRecord_flipsToTriaged_clearsFuse() {
        UUID visitId = UUID.randomUUID();
        User caller = actor();
        Visit visit = placedArrival(visitId, VisitStatus.AWAITING_TRIAGE, TriageCategory.RED);
        when(visitService.findVisitOrThrow(visitId)).thenReturn(visit);
        when(triageRecordRepository.findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId))
                .thenReturn(Optional.empty());
        when(emsRunRepository.findByVisitIdAndIsActiveTrue(visitId)).thenReturn(Optional.of(
                EmsRun.builder().hospital(new Hospital()).fieldTewsScore(7).build()));
        when(triageRecordRepository.save(any(TriageRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        triageService.confirmFieldTriage(visitId);

        // The visit is now formally triaged and the EMS re-triage fuse is discharged.
        assertEquals(VisitStatus.TRIAGED, visit.getStatus());
        assertNull(visit.getEdRetriageDueAt());

        ArgumentCaptor<TriageRecord> saved = ArgumentCaptor.forClass(TriageRecord.class);
        verify(triageRecordRepository).save(saved.capture());
        TriageRecord rec = saved.getValue();
        assertEquals(TriageCategory.RED, rec.getTriageCategory());   // paramedic's category preserved
        assertEquals(caller, rec.getTriagedBy());                    // attributed to the confirming clinician
        assertEquals(7, rec.getTewsScore());                         // field TEWS carried from the EMS run
        assertFalse(rec.isRetriage());
        assertFalse(rec.isSystemTriggered());                        // a human attested this
        assertTrue(rec.getDecisionPath().contains("EMS_FIELD_TRIAGE_CONFIRMED"));

        // Same zone-routed doctor notification the manual triage path fires.
        verify(alertEscalationService).createZoneRoutedAlert(
                any(Visit.class), any(TriageCategory.class), any(Integer.class), any(String.class));
    }

    @Test
    void confirmFieldTriage_rejectsNonAwaitingTriageStatus() {
        UUID visitId = UUID.randomUUID();
        actor();
        Visit visit = placedArrival(visitId, VisitStatus.TRIAGED, TriageCategory.RED);
        when(visitService.findVisitOrThrow(visitId)).thenReturn(visit);

        assertThrows(IllegalStateException.class, () -> triageService.confirmFieldTriage(visitId));
        verify(triageRecordRepository, never()).save(any());
    }

    @Test
    void confirmFieldTriage_rejectsLowerAcuityFieldCategory() {
        UUID visitId = UUID.randomUUID();
        actor();
        // YELLOW is out of scope — confirmation is only for RED/ORANGE; use the full form.
        Visit visit = placedArrival(visitId, VisitStatus.AWAITING_TRIAGE, TriageCategory.YELLOW);
        when(visitService.findVisitOrThrow(visitId)).thenReturn(visit);

        assertThrows(IllegalStateException.class, () -> triageService.confirmFieldTriage(visitId));
        verify(triageRecordRepository, never()).save(any());
    }

    @Test
    void confirmFieldTriage_rejectsWhenTriageAlreadyFiled() {
        UUID visitId = UUID.randomUUID();
        actor();
        Visit visit = placedArrival(visitId, VisitStatus.AWAITING_TRIAGE, TriageCategory.ORANGE);
        when(visitService.findVisitOrThrow(visitId)).thenReturn(visit);
        // A filed triage already owns this visit → double-confirm guard trips.
        when(triageRecordRepository.findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId))
                .thenReturn(Optional.of(TriageRecord.builder().build()));

        assertThrows(IllegalStateException.class, () -> triageService.confirmFieldTriage(visitId));
        verify(triageRecordRepository, never()).save(any());
    }
}
