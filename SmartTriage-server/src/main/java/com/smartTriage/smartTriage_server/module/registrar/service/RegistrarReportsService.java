package com.smartTriage.smartTriage_server.module.registrar.service;

import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.registrar.dto.CensusResponse;
import com.smartTriage.smartTriage_server.module.registrar.dto.IntakeLogRow;
import com.smartTriage.smartTriage_server.module.registrar.dto.UnidentifiedPatientRow;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Registrar reporting (R11) — operational front-desk reports computed from existing data with no
 * schema change: the intake log (registrations in a window), the unidentified-patient
 * reconciliation queue, and a point-in-time census. Read-only.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegistrarReportsService {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;

    /** Intake log: every visit that arrived within [from, to] (inclusive of the {@code to} day). */
    public List<IntakeLogRow> getIntakeLog(UUID hospitalId, LocalDate from, LocalDate to) {
        Instant fromI = from.atStartOfDay(KIGALI).toInstant();
        Instant toI = to.plusDays(1).atStartOfDay(KIGALI).toInstant();
        return visitRepository
                .findByHospitalIdAndArrivalTimeBetweenAndIsActiveTrueOrderByArrivalTimeDesc(hospitalId, fromI, toI)
                .stream().map(this::toIntakeRow).collect(Collectors.toList());
    }

    /** The unidentified-patient reconciliation queue (oldest placeholder first). */
    public List<UnidentifiedPatientRow> getUnidentifiedQueue(UUID hospitalId) {
        Instant now = Instant.now();
        return patientRepository
                .findByHospitalIdAndIsUnidentifiedTrueAndIsActiveTrueOrderByPlaceholderAssignedAtAsc(hospitalId)
                .stream().map(p -> UnidentifiedPatientRow.builder()
                        .patientId(p.getId())
                        .placeholderLabel(p.getPlaceholderLabel())
                        .placeholderAssignedAt(p.getPlaceholderAssignedAt())
                        .hoursWaiting(p.getPlaceholderAssignedAt() != null
                                ? Duration.between(p.getPlaceholderAssignedAt(), now).toHours() : null)
                        .build())
                .collect(Collectors.toList());
    }

    /** Point-in-time census: active visits grouped by status and by zone. */
    public CensusResponse getCensus(UUID hospitalId) {
        List<Visit> active = visitRepository
                .findActiveVisits(hospitalId, PageRequest.of(0, 5000)).getContent();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byZone = new LinkedHashMap<>();
        for (Visit v : active) {
            String status = v.getStatus() != null ? v.getStatus().name() : "UNKNOWN";
            byStatus.merge(status, 1, Integer::sum);
            String zone = v.getCurrentEdZone() != null ? v.getCurrentEdZone().name() : "UNZONED";
            byZone.merge(zone, 1, Integer::sum);
        }
        return CensusResponse.builder()
                .totalActive(active.size()).byStatus(byStatus).byZone(byZone)
                .generatedAt(Instant.now()).build();
    }

    // ── mapping ──
    private IntakeLogRow toIntakeRow(Visit v) {
        Patient p = v.getPatient();
        String name = p != null
                ? ((nz(p.getFirstName()) + " " + nz(p.getLastName())).trim()) : "";
        if (name.isBlank()) name = "Unidentified";
        Integer age = (p != null && p.getDateOfBirth() != null)
                ? Period.between(p.getDateOfBirth(), LocalDate.now()).getYears() : null;
        return IntakeLogRow.builder()
                .visitNumber(v.getVisitNumber())
                .arrivalTime(v.getArrivalTime())
                .arrivalMode(v.getArrivalMode() != null ? v.getArrivalMode().name() : null)
                .status(v.getStatus() != null ? v.getStatus().name() : null)
                .patientName(name)
                .ageYears(age)
                .sex(p != null && p.getGender() != null ? p.getGender().name() : null)
                .zone(v.getCurrentEdZone() != null ? v.getCurrentEdZone().name() : null)
                .unidentified(p != null && p.isUnidentified())
                .build();
    }

    private static String nz(String s) { return s != null ? s : ""; }
}
