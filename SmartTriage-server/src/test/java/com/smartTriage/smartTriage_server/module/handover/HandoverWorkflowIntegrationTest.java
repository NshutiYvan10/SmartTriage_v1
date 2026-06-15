package com.smartTriage.smartTriage_server.module.handover;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.HandoverReportType;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
import com.smartTriage.smartTriage_server.module.handover.service.HandoverPdfService;
import com.smartTriage.smartTriage_server.module.handover.service.HandoverReportService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end handover against REAL PostgreSQL: an unidentified Direct-Resus
 * patient's generated report must FLAG the unresolved identity and the
 * physical location (the gaps the audit found), and the report must render to
 * a valid letterheaded PDF.
 */
@Transactional
class HandoverWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private HandoverReportService handoverReportService;
    @Autowired private HandoverPdfService handoverPdfService;

    @Test
    void unidentifiedPatientReport_flagsIdentityAndLocation_andRendersPdf() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Hospital hospital = new Hospital();
        hospital.setName("IT Handover " + suffix);
        hospital.setHospitalCode("HND-" + suffix);
        hospital = hospitalRepository.save(hospital);

        // Unidentified placeholder, assigned 3h ago.
        Patient patient = Patient.builder()
                .firstName("Unknown")
                .lastName("Alpha")
                .hospital(hospital)
                .isUnidentified(true)
                .placeholderLabel("Alpha")
                .placeholderAssignedAt(Instant.now().minus(3, ChronoUnit.HOURS))
                .build();
        patient = patientRepository.save(patient);

        Visit visit = new Visit();
        visit.setPatient(patient);
        visit.setHospital(hospital);
        visit.setVisitNumber("HND-V-" + suffix);
        visit.setArrivalTime(Instant.now().minus(2, ChronoUnit.HOURS));
        visit.setStatus(VisitStatus.TRIAGED);
        visit.setCurrentEdZone(EdZone.RESUS);
        visit.setCurrentTriageCategory(TriageCategory.RED);
        visit = visitRepository.save(visit);

        HandoverReport report = handoverReportService.generateReport(
                visit.getId(), HandoverReportType.SHIFT_HANDOVER, "Dr Test", null);

        // Completeness gaps now closed:
        String summary = report.getPatientSummary();
        assertTrue(summary.contains("UNIDENTIFIED"), "must flag unresolved identity");
        assertTrue(summary.contains("Alpha"), "must name the placeholder");
        assertTrue(summary.contains("Location"), "must state physical location");
        assertTrue(summary.contains("RESUS"), "must include the current zone");

        // PDF renders as a valid, non-trivial document.
        HandoverPdfService.RenderedPdf pdf = handoverPdfService.renderDocument(report.getId());
        assertTrue(pdf.bytes().length > 800);
        assertEquals("%PDF", new String(pdf.bytes(), 0, 4, StandardCharsets.US_ASCII));
        assertTrue(pdf.filename().startsWith("handover-") && pdf.filename().endsWith(".pdf"));
    }
}
