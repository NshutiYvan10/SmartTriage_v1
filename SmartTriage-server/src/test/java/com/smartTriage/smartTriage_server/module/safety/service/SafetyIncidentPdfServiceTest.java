package com.smartTriage.smartTriage_server.module.safety.service;

import com.smartTriage.smartTriage_server.common.enums.IncidentSeverity;
import com.smartTriage.smartTriage_server.common.enums.IncidentStatus;
import com.smartTriage.smartTriage_server.common.enums.IncidentType;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link SafetyIncidentPdfService#render} — proves OpenPDF produces a valid PDF
 * (correct magic bytes, non-trivial size) for both a freshly-reported incident and a fully
 * investigated-and-closed one, without a database.
 */
class SafetyIncidentPdfServiceTest {

    private final SafetyIncidentPdfService service = new SafetyIncidentPdfService();

    private static void assertIsPdf(byte[] pdf) {
        assertTrue(pdf.length > 800, "PDF should be non-trivial in size");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "PDF magic bytes");
    }

    @Test
    void rendersValidPdfForReportedIncident() {
        Hospital h = new Hospital();
        h.setName("Kigali Emergency Hospital");
        h.setHospitalCode("KGL-ED");
        SafetyIncident i = SafetyIncident.builder()
                .hospital(h)
                .incidentNumber("SI-20260623-00001")
                .incidentType(IncidentType.MEDICATION_ERROR)
                .severity(IncidentSeverity.MODERATE_HARM)
                .status(IncidentStatus.REPORTED)
                .incidentDateTime(Instant.now())
                .locationInHospital("Zone GENERAL, Bed A1")
                .description("Wrong dose of paracetamol charted; caught before administration.")
                .reportedByName("RN Keza")
                .reportedByRole("NURSE")
                .reportedAt(Instant.now())
                .patientHarmed(false)
                .build();
        assertIsPdf(service.render(i, "Dr Test Exporter"));
    }

    @Test
    void rendersValidPdfForClosedIncidentWithFullLifecycle() {
        Hospital h = new Hospital();
        h.setName("Kigali Emergency Hospital");
        SafetyIncident i = SafetyIncident.builder()
                .hospital(h)
                .incidentNumber("SI-20260623-00002")
                .incidentType(IncidentType.FALL)
                .severity(IncidentSeverity.SEVERE_HARM)
                .status(IncidentStatus.CLOSED)
                .incidentDateTime(Instant.now())
                .description("Patient fall from trolley.")
                .reportedByName("Dr A")
                .patientHarmed(true)
                .investigatorName("Safety Officer")
                .investigationStartedAt(Instant.now())
                .investigationCompletedAt(Instant.now())
                .rootCauseAnalysis("Trolley side rail not raised.")
                .rootCauseCategory("Equipment/Process")
                .correctiveAction("Side-rail checklist added to transfer protocol.")
                .correctiveActionOwner("Charge Nurse")
                .closedByName("Quality Lead")
                .closedAt(Instant.now())
                .lessonsLearned("Always raise side rails during transfer.")
                .build();
        assertIsPdf(service.render(i, "Dr Test Exporter"));
    }
}
