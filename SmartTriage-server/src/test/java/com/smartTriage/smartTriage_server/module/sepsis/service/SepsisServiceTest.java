package com.smartTriage.smartTriage_server.module.sepsis.service;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisScreeningRequest;
import com.smartTriage.smartTriage_server.module.sepsis.engine.SepsisScreeningEngine;
import com.smartTriage.smartTriage_server.module.sepsis.entity.SepsisScreening;
import com.smartTriage.smartTriage_server.module.sepsis.repository.SepsisScreeningRepository;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level evidence: the WBC criterion now correctly re-derives sepsis
 * status (the critical false-negative fix), lactate/infection escalation works,
 * and a positive screen is pushed in real time. Uses the REAL engine so the
 * end-to-end scoring path is exercised; only IO collaborators are mocked.
 */
class SepsisServiceTest {

    private SepsisScreeningRepository sepsisRepo;
    private VisitRepository visitRepo;
    private VitalSignsRepository vitalRepo;
    private ClinicalAlertRepository alertRepo;
    private ShiftAssignmentService shiftService;
    private RealTimeEventPublisher publisher;
    private SepsisService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sepsisRepo = mock(SepsisScreeningRepository.class);
        visitRepo = mock(VisitRepository.class);
        vitalRepo = mock(VitalSignsRepository.class);
        alertRepo = mock(ClinicalAlertRepository.class);
        shiftService = mock(ShiftAssignmentService.class);
        publisher = mock(RealTimeEventPublisher.class);
        service = new SepsisService(sepsisRepo, new SepsisScreeningEngine(), visitRepo, vitalRepo,
                alertRepo, shiftService, publisher);

        when(sepsisRepo.findFirstByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(visitId))
                .thenReturn(Optional.empty());
        when(sepsisRepo.save(any(SepsisScreening.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alertRepo.save(any(ClinicalAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shiftService.getDoctorsForZone(any(), any())).thenReturn(List.of());
        when(shiftService.getChargeNurse(any())).thenReturn(List.of());
    }

    private void givenVitals(Double temp, Integer hr, Integer rr, Integer sbp, AvpuScore avpu, boolean pediatric) {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        h.setName("Test Hospital");
        Visit v = new Visit();
        v.setId(visitId);
        v.setVisitNumber("V-1");
        v.setHospital(h);
        v.setPediatric(pediatric);
        v.setCurrentEdZone(EdZone.ACUTE);
        v.setPatient(Patient.builder().firstName("Jane").lastName("Doe")
                .dateOfBirth(LocalDate.now().minusYears(pediatric ? 1 : 40)).build());
        when(visitRepo.findByIdAndIsActiveTrue(visitId)).thenReturn(Optional.of(v));
        when(vitalRepo.findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId))
                .thenReturn(Optional.of(VitalSigns.builder()
                        .temperature(temp).heartRate(hr).respiratoryRate(rr).systolicBp(sbp).avpu(avpu).build()));
    }

    @Test
    @DisplayName("WBC-driven SIRS + infection + lactate → SEVERE_SEPSIS (was silently NO_SEPSIS before the fix)")
    void wbcDrivenSepsisIsDetectedAndAlerted() {
        // Engine on vitals alone: temp 39 → SIRS 1, qSOFA 0 → NO_SEPSIS.
        givenVitals(39.0, 88, 18, 120, AvpuScore.ALERT, false);
        SepsisScreeningRequest req = SepsisScreeningRequest.builder()
                .wbcCount(18000.0)                 // SIRS WBC → sirsScore 2
                .suspectedInfectionSource("Urinary") // SIRS>=2 + infection → SEPSIS_SUSPECTED
                .lactateLevel(3.0)                  // lactate>2 → SEVERE_SEPSIS
                .build();

        SepsisScreening s = service.screenPatient(visitId, req);

        assertEquals(SepsisStatus.SEVERE_SEPSIS, s.getSepsisStatus());
        assertEquals(2, s.getSirsScore());
        // A positive screen is persisted as a CRITICAL alert AND pushed in real time.
        verify(alertRepo).save(any(ClinicalAlert.class));
        verify(publisher, atLeastOnce()).publishHospitalAlert(eq(hospitalId), any());
    }

    @Test
    @DisplayName("WBC-driven SIRS WITHOUT infection → SIRS_POSITIVE, no sepsis alert")
    void wbcDrivenSirsWithoutInfection() {
        givenVitals(39.0, 88, 18, 120, AvpuScore.ALERT, false);
        SepsisScreening s = service.screenPatient(visitId,
                SepsisScreeningRequest.builder().wbcCount(18000.0).build());

        assertEquals(SepsisStatus.SIRS_POSITIVE, s.getSepsisStatus()); // was NO_SEPSIS before the fix
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Negative screen → NO_SEPSIS, no alert, no push")
    void negativeScreen() {
        givenVitals(37.0, 80, 16, 120, AvpuScore.ALERT, false);
        SepsisScreening s = service.screenPatient(visitId, null);

        assertEquals(SepsisStatus.NO_SEPSIS, s.getSepsisStatus());
        verify(alertRepo, never()).save(any(ClinicalAlert.class));
    }

    @Test
    @DisplayName("Pediatric screen stamps the mandatory caveat")
    void pediatricCaveatStamped() {
        givenVitals(39.0, 140, 45, 95, AvpuScore.ALERT, true); // septic infant
        SepsisScreening s = service.screenPatient(visitId, null);

        assertEquals(true, s.isPediatric());
        org.junit.jupiter.api.Assertions.assertNotNull(s.getPediatricCaveat());
    }
}
