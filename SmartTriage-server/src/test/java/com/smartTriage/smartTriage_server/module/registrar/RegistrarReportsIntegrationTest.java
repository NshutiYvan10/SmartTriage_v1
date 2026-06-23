package com.smartTriage.smartTriage_server.module.registrar;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import com.smartTriage.smartTriage_server.module.registrar.dto.CensusResponse;
import com.smartTriage.smartTriage_server.module.registrar.dto.IntakeLogRow;
import com.smartTriage.smartTriage_server.module.registrar.dto.UnidentifiedPatientRow;
import com.smartTriage.smartTriage_server.module.registrar.service.RegistrarReportsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against REAL PostgreSQL for the R11 registrar reporting pack
 * ({@link RegistrarReportsService}): proves the intake-log arrival-window finder, the
 * hospital-scoped unidentified-patient reconciliation queue, and the live census all compute
 * correctly AND stay strictly hospital-scoped — a patient/visit at hospital B never leaks into
 * hospital A's reports. These are read paths over real Flyway-migrated schema + real queries, the
 * failure class unit tests can't catch (derived-finder name typos, column/zone mapping).
 */
@Transactional
class RegistrarReportsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private PatientService patientService;
    @Autowired private PatientRepository patientRepository;
    @Autowired private RegistrarReportsService registrarReportsService;

    private Hospital hospital(String suffix) {
        return hospitalRepository.save(Hospital.builder()
                .name("RR " + suffix).hospitalCode("RR-" + suffix).build());
    }

    private RegisterPatientRequest reg(UUID hospitalId, String first, String last) {
        return RegisterPatientRequest.builder()
                .firstName(first).lastName(last)
                .dateOfBirth(LocalDate.now().minusYears(40)).gender(Gender.FEMALE)
                .bloodType("O+").hospitalId(hospitalId).chiefComplaint("test").build();
    }

    @Test
    void intakeLog_unidentifiedQueue_andCensus_areCorrect_andStrictlyHospitalScoped() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A-" + s);
        Hospital b = hospital("B-" + s);

        // Two IDENTIFIED registrations at hospital A → two visits with arrivalTime = now.
        UUID v1Patient = patientService.registerPatientWithVisit(reg(a.getId(), "Aline", "Mukamana"))
                .getPatient().getId();
        var visit2 = patientService.registerPatientWithVisit(reg(a.getId(), "Beata", "Ingabire"));
        assertNotNull(v1Patient);

        // One registration at a DIFFERENT hospital B (must never appear in A's reports).
        var visitB = patientService.registerPatientWithVisit(reg(b.getId(), "Other", "Hospital"));
        String bVisitNumber = visitB.getVisit().getVisitNumber();

        // One UNIDENTIFIED ("John Doe") patient at hospital A — a placeholder awaiting identity
        // resolution, assigned 2h ago. Saved directly (the real path is Direct-Resus / EMS arrival).
        Patient unidentified = patientRepository.save(Patient.builder()
                .firstName("Unidentified").lastName("Male-001")
                .hospital(a)
                .isUnidentified(true)
                .placeholderLabel("UNKNOWN-MALE-" + s)
                .placeholderAssignedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .build());

        // ── Intake log: window spanning yesterday..tomorrow captures both A registrations,
        //    and NOT hospital B's. ──
        List<IntakeLogRow> intake = registrarReportsService.getIntakeLog(
                a.getId(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertTrue(intake.size() >= 2, "both hospital-A registrations must appear in the intake log");
        assertTrue(intake.stream().anyMatch(r -> "Aline Mukamana".equals(r.patientName())),
                "first registration must appear");
        assertTrue(intake.stream().anyMatch(r -> visit2.getVisit().getVisitNumber().equals(r.visitNumber())),
                "second registration must appear by visit number");
        assertFalse(intake.stream().anyMatch(r -> bVisitNumber.equals(r.visitNumber())),
                "hospital B's visit must NOT leak into hospital A's intake log");
        // age/sex mapping exercised
        assertTrue(intake.stream().anyMatch(r -> r.ageYears() != null && r.ageYears() == 40),
                "age must be computed from date of birth");

        // ── Unidentified queue: hospital A has exactly the one placeholder; hospital B has none. ──
        List<UnidentifiedPatientRow> queueA = registrarReportsService.getUnidentifiedQueue(a.getId());
        assertEquals(1, queueA.size(), "hospital A has exactly one unidentified patient");
        UnidentifiedPatientRow row = queueA.get(0);
        assertEquals(unidentified.getId(), row.patientId());
        assertEquals("UNKNOWN-MALE-" + s, row.placeholderLabel());
        assertNotNull(row.hoursWaiting(), "hours-waiting is derived from placeholderAssignedAt");
        assertTrue(row.hoursWaiting() >= 1, "assigned ~2h ago → at least 1h waiting");

        assertTrue(registrarReportsService.getUnidentifiedQueue(b.getId()).isEmpty(),
                "hospital B has no unidentified patients");

        // ── Census: hospital A counts its active visits (>= 2), grouped by status. ──
        CensusResponse censusA = registrarReportsService.getCensus(a.getId());
        assertTrue(censusA.totalActive() >= 2, "census counts hospital A's active visits");
        assertFalse(censusA.byStatus().isEmpty(), "census breaks down by visit status");
        int statusSum = censusA.byStatus().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(censusA.totalActive(), statusSum, "status breakdown must sum to the total");
        assertNotNull(censusA.generatedAt());
    }
}
