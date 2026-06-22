package com.smartTriage.smartTriage_server.module.handover.service;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.HandoverReportType;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
import com.smartTriage.smartTriage_server.module.handover.repository.HandoverReportRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationScheduleService;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-independent unit test for {@link HandoverReportService#generateReport} — proves the
 * report ASSEMBLES its doctor-critical sections from the visit (identity, doctor-of-record
 * assessment, dedicated medication-audit) and persists, without a database. Sections with no
 * data fall back gracefully (empty-mock finders) rather than NPE. (Full multi-domain assembly
 * against a real schema is covered by HandoverWorkflowIntegrationTest in CI.)
 */
class HandoverReportServiceTest {

    private final HandoverReportRepository handoverReportRepository = mock(HandoverReportRepository.class);
    private final VisitRepository visitRepository = mock(VisitRepository.class);
    private final MedicationScheduleService medicationScheduleService = mock(MedicationScheduleService.class);
    // These three finders return Page (not Optional/List), so Mockito's default is null — stub to Page.empty().
    private final TriageRecordRepository triageRecordRepository = mock(TriageRecordRepository.class);
    private final VitalSignsRepository vitalSignsRepository = mock(VitalSignsRepository.class);
    private final ClinicalAlertRepository clinicalAlertRepository = mock(ClinicalAlertRepository.class);
    private final ClinicalDocumentRepository clinicalDocumentRepository = mock(ClinicalDocumentRepository.class);

    // 25-dependency service; only the three above are stubbed — the rest return Mockito
    // defaults (empty list / Optional.empty), which the null-safe section builders handle.
    private final HandoverReportService service = new HandoverReportService(
            handoverReportRepository,
            visitRepository,
            mock(com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository.class),
            triageRecordRepository,
            vitalSignsRepository,
            mock(com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository.class),
            mock(com.smartTriage.smartTriage_server.module.clinical.repository.DiagnosisRepository.class),
            mock(com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository.class),
            clinicalAlertRepository,
            mock(com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository.class),
            mock(com.smartTriage.smartTriage_server.module.patient.repository.PatientAllergyRepository.class),
            mock(com.smartTriage.smartTriage_server.module.isolation.repository.InfectionScreeningRepository.class),
            mock(com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository.class),
            mock(com.smartTriage.smartTriage_server.module.ems.repository.EmsInterventionRepository.class),
            mock(com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository.class),
            mock(com.smartTriage.smartTriage_server.module.sepsis.repository.SepsisScreeningRepository.class),
            mock(com.smartTriage.smartTriage_server.module.icu.repository.IcuEscalationRepository.class),
            mock(com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository.class),
            mock(com.smartTriage.smartTriage_server.module.clinicalsigns.repository.ClinicalSignEventRepository.class),
            clinicalDocumentRepository,
            mock(com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository.class),
            mock(com.smartTriage.smartTriage_server.module.pathway.repository.PathwayActivationRepository.class),
            mock(com.smartTriage.smartTriage_server.module.zonetransfer.repository.ZoneTransferRepository.class),
            mock(com.smartTriage.smartTriage_server.module.patient.repository.PatientChronicConditionRepository.class),
            medicationScheduleService);

    @Test
    void generateReport_assemblesDoctorCriticalSections_andPersists() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setName("Kigali Emergency Hospital");
        h.setHospitalCode("KGL-ED");

        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setFirstName("Jean");
        p.setLastName("Uwimana");

        User doctor = new User();
        doctor.setId(UUID.randomUUID());
        doctor.setFirstName("Grace");
        doctor.setLastName("Habimana");

        Visit v = new Visit();
        v.setId(UUID.randomUUID());
        v.setVisitNumber("V-HND-1");
        v.setPatient(p);
        v.setHospital(h);
        v.setArrivalTime(Instant.now().minus(2, ChronoUnit.HOURS));
        v.setStatus(VisitStatus.UNDER_TREATMENT);
        v.setCurrentEdZone(EdZone.ACUTE);
        v.setCurrentTriageCategory(TriageCategory.ORANGE);
        v.setChiefComplaint("Chest pain");
        v.setPrimaryClinician(doctor);

        when(visitRepository.findByIdAndIsActiveTrue(v.getId())).thenReturn(Optional.of(v));
        // Paged finders return Page (Mockito default null) — give empty pages.
        when(triageRecordRepository.findByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(any(), any()))
                .thenReturn(Page.empty());
        when(vitalSignsRepository.findByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(any(), any()))
                .thenReturn(Page.empty());
        when(clinicalAlertRepository.findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Page.empty());
        when(clinicalDocumentRepository.findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Page.empty());
        when(medicationScheduleService.buildMedicationAuditText(v))
                .thenReturn("Order: Heparin 5000 units SC q12h. Dose 1 given 14:00 by RN Keza, witnessed.");
        when(handoverReportRepository.save(any(HandoverReport.class))).thenAnswer(i -> i.getArgument(0));

        HandoverReport r = service.generateReport(
                v.getId(), HandoverReportType.SHIFT_HANDOVER, "Dr Test", "Stable, await troponin.");

        // Identity assembled into the patient summary.
        assertThat(r.getPatientSummary()).contains("Jean Uwimana");
        // Doctor-of-record (accountability) assembled into Assessment & Plan.
        assertThat(r.getPlanOfCare()).contains("Habimana");
        // The dedicated medication-audit section is wired through.
        assertThat(r.getMedicationAudit()).contains("Heparin 5000 units");
        // Metadata + persistence.
        assertThat(r.getReportType()).isEqualTo(HandoverReportType.SHIFT_HANDOVER);
        assertThat(r.getGeneratedByName()).isEqualTo("Dr Test");
        assertThat(r.getNotes()).isEqualTo("Stable, await troponin.");
        verify(handoverReportRepository).save(any(HandoverReport.class));
    }
}
