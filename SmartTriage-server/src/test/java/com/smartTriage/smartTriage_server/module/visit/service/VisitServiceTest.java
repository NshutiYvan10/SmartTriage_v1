package com.smartTriage.smartTriage_server.module.visit.service;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.enums.DispositionType;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.bed.service.BedService;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import com.smartTriage.smartTriage_server.module.visit.dto.DispositionRequest;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.security.ClinicalAuthz;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Item #7 — discharge destination data-loss fix + discharge-summary requirement. */
class VisitServiceTest {

    private final VisitRepository visitRepository = mock(VisitRepository.class);
    private final DeviceSessionRepository deviceSessionRepository = mock(DeviceSessionRepository.class);
    private final BedService bedService = mock(BedService.class);
    private final ClinicalDocumentRepository clinicalDocumentRepository = mock(ClinicalDocumentRepository.class);

    private final VisitService service = new VisitService(
            visitRepository,
            mock(PatientService.class),
            mock(HospitalService.class),
            deviceSessionRepository,
            bedService,
            mock(com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService.class),
            mock(ClinicalAuthz.class),
            mock(com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository.class),
            mock(com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository.class),
            mock(com.smartTriage.smartTriage_server.module.icu.repository.IcuEscalationRepository.class),
            mock(RealTimeEventPublisher.class),
            clinicalDocumentRepository);

    private final UUID VISIT = UUID.randomUUID();

    private Visit mappableVisit() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setFirstName("Jane");
        p.setLastName("Doe");
        Visit v = new Visit();
        v.setId(VISIT);
        v.setVisitNumber("V-DISP-1");
        v.setArrivalTime(Instant.now());
        v.setPatient(p);
        v.setHospital(h);
        return v;
    }

    @Test
    void recordDisposition_admittedToWard_persistsDestinationWard() {
        Visit v = mappableVisit();
        when(visitRepository.findByIdAndIsActiveTrue(VISIT)).thenReturn(Optional.of(v));
        when(visitRepository.save(any(Visit.class))).thenAnswer(i -> i.getArgument(0));
        when(deviceSessionRepository.findByVisitIdAndSessionActiveTrueAndIsActiveTrue(VISIT))
                .thenReturn(Optional.empty());

        service.recordDisposition(VISIT, DispositionRequest.builder()
                .dispositionType(DispositionType.ADMITTED_TO_WARD)
                .destinationWard("Ward 3B — Internal Medicine")
                .notes("For ongoing IV antibiotics")
                .build());

        ArgumentCaptor<Visit> cap = ArgumentCaptor.forClass(Visit.class);
        verify(visitRepository).save(cap.capture());
        // The destination is no longer silently discarded.
        assertThat(cap.getValue().getDispositionDestinationWard()).isEqualTo("Ward 3B — Internal Medicine");
    }

    @Test
    void recordDisposition_transferred_persistsReceivingFacility() {
        Visit v = mappableVisit();
        when(visitRepository.findByIdAndIsActiveTrue(VISIT)).thenReturn(Optional.of(v));
        when(visitRepository.save(any(Visit.class))).thenAnswer(i -> i.getArgument(0));
        when(deviceSessionRepository.findByVisitIdAndSessionActiveTrueAndIsActiveTrue(VISIT))
                .thenReturn(Optional.empty());

        service.recordDisposition(VISIT, DispositionRequest.builder()
                .dispositionType(DispositionType.TRANSFERRED)
                .receivingFacility("Kigali University Teaching Hospital")
                .build());

        ArgumentCaptor<Visit> cap = ArgumentCaptor.forClass(Visit.class);
        verify(visitRepository).save(cap.capture());
        assertThat(cap.getValue().getDispositionReceivingFacility())
                .isEqualTo("Kigali University Teaching Hospital");
    }

    @Test
    void recordDisposition_dischargeHome_withoutDischargeSummary_isRejected() {
        Visit v = mappableVisit();
        when(visitRepository.findByIdAndIsActiveTrue(VISIT)).thenReturn(Optional.of(v));
        when(clinicalDocumentRepository.existsByVisitIdAndDocumentTypeAndIsActiveTrue(
                eq(VISIT), eq(ClinicalDocumentType.DISCHARGE_SUMMARY))).thenReturn(false);

        assertThatThrownBy(() -> service.recordDisposition(VISIT, DispositionRequest.builder()
                .dispositionType(DispositionType.DISCHARGED_HOME).build()))
                .isInstanceOf(ClinicalBusinessException.class)
                .hasMessageContaining("discharge summary");
        verify(visitRepository, never()).save(any());
    }

    @Test
    void recordDisposition_dischargeHome_withDischargeSummary_succeeds() {
        Visit v = mappableVisit();
        when(visitRepository.findByIdAndIsActiveTrue(VISIT)).thenReturn(Optional.of(v));
        when(visitRepository.save(any(Visit.class))).thenAnswer(i -> i.getArgument(0));
        when(deviceSessionRepository.findByVisitIdAndSessionActiveTrueAndIsActiveTrue(VISIT))
                .thenReturn(Optional.empty());
        when(clinicalDocumentRepository.existsByVisitIdAndDocumentTypeAndIsActiveTrue(
                eq(VISIT), eq(ClinicalDocumentType.DISCHARGE_SUMMARY))).thenReturn(true);

        service.recordDisposition(VISIT, DispositionRequest.builder()
                .dispositionType(DispositionType.DISCHARGED_HOME).build());

        verify(visitRepository).save(any(Visit.class));
    }
}
