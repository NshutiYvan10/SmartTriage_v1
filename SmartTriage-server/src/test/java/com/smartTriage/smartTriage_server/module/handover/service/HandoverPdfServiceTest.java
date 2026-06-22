package com.smartTriage.smartTriage_server.module.handover.service;

import com.smartTriage.smartTriage_server.common.enums.HandoverReportType;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
import com.smartTriage.smartTriage_server.module.handover.repository.HandoverReportRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit test for {@link HandoverPdfService#render} — proves OpenPDF produces a
 * valid PDF document (correct magic bytes, non-trivial size) for both an
 * identified and an unidentified patient, without touching a database.
 */
class HandoverPdfServiceTest {

    private final HandoverPdfService service =
            new HandoverPdfService(mock(HandoverReportRepository.class));

    private HandoverReport report(Patient patient) {
        Hospital h = new Hospital();
        h.setName("Kigali Emergency Hospital");
        h.setHospitalCode("KGL-ED");
        Visit v = new Visit();
        v.setVisitNumber("V-IT-1");
        v.setPatient(patient);
        return HandoverReport.builder()
                .hospital(h)
                .visit(v)
                .reportType(HandoverReportType.SHIFT_HANDOVER)
                .generatedAt(Instant.now())
                .generatedByName("Dr Test")
                .patientSummary("Name: Test Patient\nLocation: Zone GENERAL, Bed A1\nAllergies: None known")
                .triageSummary("Current Category: YELLOW")
                .medicationAudit("Order: Paracetamol 1 g PO — 1 dose given")
                .planOfCare("Assessment: stable. Plan: observe 2h, recheck vitals.")
                .build();
    }

    private static void assertIsPdf(byte[] pdf) {
        assertTrue(pdf.length > 800, "PDF should be non-trivial in size");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "PDF magic bytes");
    }

    @Test
    void rendersValidPdfForIdentifiedPatient() {
        Patient p = new Patient();
        p.setFirstName("Marie");
        p.setLastName("Uwimana");
        p.setMedicalRecordNumber("KGL-ED-100");
        assertIsPdf(service.render(report(p)));
    }

    @Test
    void rendersValidPdfForUnidentifiedPatient() {
        // Exercises the unidentified-banner branch of the renderer.
        Patient p = new Patient();
        p.setFirstName("Unknown");
        p.setLastName("Alpha");
        p.setUnidentified(true);
        p.setPlaceholderLabel("Alpha");
        p.setPlaceholderAssignedAt(Instant.now());
        assertIsPdf(service.render(report(p)));
    }

    @Test
    void rendersValidPdfWithV73AndMedicationSections() {
        // The V73 sections (prehospital / acute protocols / procedures-documents) and the
        // V67 medication-audit section must render into the PDF — they were added after the
        // original PDF test and were previously unexercised.
        Hospital h = new Hospital();
        h.setName("Kigali Emergency Hospital");
        h.setHospitalCode("KGL-ED");
        Patient p = new Patient();
        p.setFirstName("Jean");
        p.setLastName("Uwimana");
        Visit v = new Visit();
        v.setVisitNumber("V-V73-1");
        v.setPatient(p);

        HandoverReport report = HandoverReport.builder()
                .hospital(h)
                .visit(v)
                .reportType(HandoverReportType.SHIFT_HANDOVER)
                .generatedAt(Instant.now())
                .generatedByName("Dr Test")
                .patientSummary("Name: Jean Uwimana\nLocation: Zone ACUTE, Bed 3")
                .prehospitalSummary("EMS: SAMU (SAMU-7). Mechanism: RTA — motorcycle vs car. "
                        + "Field triage: ORANGE (TEWS 5). Interventions: cervical collar, IV access.")
                .acuteProtocols("SEPSIS: screening positive, bundle started 14:10.\n"
                        + "FAST TRACK: STEMI pathway active, cath lab notified.")
                .proceduresDocuments("PROCEDURE NOTE — Wound closure, left forearm (signed, Dr Mukamana).")
                .medicationAudit("Order: Heparin 5000 units SC q12h. Dose 1 given 14:00 by RN Keza, "
                        + "witnessed by RN Niyonsaba.")
                .planOfCare("Doctor of Record: Dr Habimana\nClinical Impression: ACS, await troponin.")
                .build();

        assertIsPdf(service.render(report));
    }
}
