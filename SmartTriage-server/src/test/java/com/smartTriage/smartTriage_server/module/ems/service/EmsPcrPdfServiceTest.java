package com.smartTriage.smartTriage_server.module.ems.service;

import com.smartTriage.smartTriage_server.common.enums.EmsInterventionType;
import com.smartTriage.smartTriage_server.common.enums.EmsRunStatus;
import com.smartTriage.smartTriage_server.common.enums.EmsService;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsIntervention;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link EmsPcrPdfService#render} — proves OpenPDF produces a valid PCR PDF (correct
 * magic bytes, non-trivial size) for both an identified (Visit→Patient linked) run and an
 * unidentified field-only run (no visit), without touching a database. Also checks the filename.
 */
class EmsPcrPdfServiceTest {

    private final EmsPcrPdfService service = new EmsPcrPdfService();

    private static void assertIsPdf(byte[] pdf) {
        assertTrue(pdf.length > 800, "PDF should be non-trivial in size");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "PDF magic bytes");
    }

    private Hospital hospital() {
        Hospital h = new Hospital();
        h.setName("Kigali Emergency Hospital");
        h.setHospitalCode("KGL-ED");
        return h;
    }

    private List<EmsIntervention> interventions() {
        return List.of(
                EmsIntervention.builder().type(EmsInterventionType.OXYGEN).detail("15 L NRB")
                        .givenAt(Instant.now()).givenByName("Para A").build(),
                EmsIntervention.builder().type(EmsInterventionType.IV_ACCESS).detail("18G left ACF")
                        .route("IV").givenByName("Para A").outcome("patent").build());
    }

    @Test
    void rendersValidPcrForIdentifiedRun() {
        Patient p = new Patient();
        p.setFirstName("Jean");
        p.setLastName("Mugisha");
        p.setMedicalRecordNumber("KGL-ED-200");
        p.setNationalId("1199870012345678");
        Visit v = new Visit();
        v.setVisitNumber("V-EMS-1");
        v.setPatient(p);

        EmsRun run = EmsRun.builder()
                .hospital(hospital()).visit(v)
                .service(EmsService.SAMU).unitCallsign("SAMU-7").paramedicName("Para A")
                .status(EmsRunStatus.HANDED_OFF).lightsActive(true)
                .dispatchedAt(Instant.now()).sceneArrivedAt(Instant.now()).edArrivedAt(Instant.now())
                .incidentLocation("KN 5 Rd").mechanism("RTA — motorcycle vs car")
                .historySummary("Helmeted rider, brief LOC").injuriesObserved("Left forearm deformity")
                .fieldTriageCategory("ORANGE").fieldTewsScore(5).fieldTriageReason("RR 24, HR 118")
                .fieldGcs(15).fieldRespRate(24).fieldHr(118).fieldSbp(105).fieldDbp(70).fieldSpo2(95)
                .fieldTemp(new BigDecimal("36.8")).fieldGlucose(new BigDecimal("6.4"))
                .handedOffToName("RN Keza").handoverAcknowledgementText("Received, to ACUTE bay 3")
                .notes("Stable throughout transport.")
                .build();

        assertIsPdf(service.render(run, interventions(), "Dr Test Exporter"));
        assertTrue(service.filename(run).startsWith("pcr-"));
        assertTrue(service.filename(run).endsWith(".pdf"));
    }

    @Test
    void rendersValidPcrForUnidentifiedFieldRun() {
        // No visit/patient — exercises the "Unidentified field patient" + safePatient(null) branch.
        EmsRun run = EmsRun.builder()
                .hospital(hospital())
                .service(EmsService.OTHER).paramedicName("Para B")
                .status(EmsRunStatus.EN_ROUTE)
                .dispatchedAt(Instant.now())
                .patientAgeYears(40).patientSex("MALE")
                .incidentLocation("Field")
                .fieldTriageCategory("RED").fieldTewsScore(9)
                .fieldGcs(8).fieldRespRate(30).fieldHr(140)
                .build();

        assertIsPdf(service.render(run, List.of(), "SmartTriage user"));
        assertTrue(service.filename(run).startsWith("pcr-"));
    }
}
