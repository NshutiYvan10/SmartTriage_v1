package com.smartTriage.smartTriage_server.module.visit.service;

import com.smartTriage.smartTriage_server.common.enums.DispositionType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.bed.service.BedService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import com.smartTriage.smartTriage_server.module.visit.dto.CreateVisitRequest;
import com.smartTriage.smartTriage_server.module.visit.dto.DispositionRequest;
import com.smartTriage.smartTriage_server.module.visit.dto.VisitResponse;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.mapper.VisitMapper;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Visit service — manages ED encounters.
 * A visit is the central workflow record:
 * Registration → Triage → Monitoring → Assessment → Disposition
 *
 * Critical: arrival_time is medico-legally important and must be
 * system-generated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisitService {

    private final VisitRepository visitRepository;
    private final PatientService patientService;
    private final HospitalService hospitalService;
    private final DeviceSessionRepository deviceSessionRepository;
    private final BedService bedService;

    private static final AtomicLong visitCounter = new AtomicLong(0);

    @Transactional
    public VisitResponse createVisit(CreateVisitRequest request) {
        Patient patient = patientService.findPatientOrThrow(request.getPatientId());
        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());

        Visit visit = Visit.builder()
                .patient(patient)
                .hospital(hospital)
                .visitNumber(generateVisitNumber(hospital.getHospitalCode()))
                .arrivalMode(request.getArrivalMode())
                .arrivalTime(Instant.now()) // System-generated — medico-legal requirement
                .chiefComplaint(request.getChiefComplaint())
                .status(VisitStatus.REGISTERED)
                .isPediatric(patient.isPediatric())
                .referringFacility(request.getReferringFacility())
                .build();

        visit = visitRepository.save(visit);

        log.info("Visit created: {} for patient {} at hospital {}",
                visit.getVisitNumber(),
                patient.getMedicalRecordNumber(),
                hospital.getHospitalCode());

        return VisitMapper.toResponse(visit);
    }

    public VisitResponse getVisitById(UUID id) {
        Visit visit = findVisitOrThrow(id);
        return VisitMapper.toResponse(visit);
    }

    public Page<VisitResponse> getActiveVisits(UUID hospitalId, Pageable pageable) {
        return visitRepository.findActiveVisits(hospitalId, pageable)
                .map(VisitMapper::toResponse);
    }

    public Page<VisitResponse> getVisitsByPatient(UUID patientId, Pageable pageable) {
        return visitRepository.findByPatientIdAndIsActiveTrue(patientId, pageable)
                .map(VisitMapper::toResponse);
    }

    public Page<VisitResponse> getVisitsByStatus(UUID hospitalId, VisitStatus status, Pageable pageable) {
        return visitRepository.findByHospitalIdAndStatus(hospitalId, status, pageable)
                .map(VisitMapper::toResponse);
    }

    @Transactional
    public VisitResponse updateVisitStatus(UUID visitId, VisitStatus newStatus) {
        Visit visit = findVisitOrThrow(visitId);
        visit.setStatus(newStatus);

        // Record assessment start time when doctor accepts the patient
        if (newStatus == VisitStatus.UNDER_ASSESSMENT && visit.getAssessmentStartTime() == null) {
            visit.setAssessmentStartTime(Instant.now());
        }

        visit = visitRepository.save(visit);
        log.info("Visit {} status updated to {}", visit.getVisitNumber(), newStatus);
        return VisitMapper.toResponse(visit);
    }

    public Visit findVisitOrThrow(UUID id) {
        return visitRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", id));
    }

    // ====================================================================
    // ZONE-BASED QUERIES ("My Patients")
    // ====================================================================

    /**
     * Get active visits whose canonical zone equals the given zone.
     * Used by doctors to see only patients in their assigned zone.
     *
     * <p>Phase 1 of the zone-routing workflow — reads
     * {@code visits.current_ed_zone} directly rather than deriving
     * from triage category. This honours per-hospital configuration
     * (peds resus, ambulatory zone) and supports the AMBULATORY +
     * PEDIATRIC zones that the previous category-mapping couldn't.
     */
    public List<VisitResponse> getVisitsByZone(UUID hospitalId, EdZone zone) {
        return visitRepository.findActiveVisitsInZones(
                hospitalId, java.util.List.of(zone),
                org.springframework.data.domain.PageRequest.of(0, 200))
                .stream()
                .map(VisitMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Phase 1 zone-scoped list with multiple zones at once. Used by
     * shifts that cover more than one zone (e.g. doctor covering
     * GENERAL + AMBULATORY) and by Phase 2 to surface
     * pending-transfer-into patients alongside the home zone's list.
     */
    public Page<VisitResponse> getVisitsInZones(
            UUID hospitalId, java.util.Collection<EdZone> zones, Pageable pageable) {
        if (zones == null || zones.isEmpty()) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        return visitRepository.findActiveVisitsInZones(hospitalId, zones, pageable)
                .map(VisitMapper::toResponse);
    }

    // ====================================================================
    // DISPOSITION WORKFLOW
    // ====================================================================

    /**
     * Record patient disposition — the final step of the ED visit.
     * Sets disposition fields, transitions visit status, and stops any active
     * IoT monitoring session.
     */
    @Transactional
    public VisitResponse recordDisposition(UUID visitId, DispositionRequest request) {
        Visit visit = findVisitOrThrow(visitId);

        // Set disposition fields
        visit.setDispositionType(request.getDispositionType());
        visit.setDispositionTime(Instant.now());
        visit.setDispositionNotes(request.getNotes());

        // Map DispositionType → VisitStatus
        VisitStatus finalStatus = mapDispositionToStatus(request.getDispositionType());
        visit.setStatus(finalStatus);

        Visit savedVisit = visitRepository.save(visit);

        // Auto-stop any active IoT monitoring session for this visit
        deviceSessionRepository.findByVisitIdAndSessionActiveTrueAndIsActiveTrue(savedVisit.getId())
                .ifPresent(session -> {
                    session.endSession("System", "Patient disposition: " + request.getDispositionType());
                    deviceSessionRepository.save(session);
                    log.info("IoT session auto-stopped for visit {} on disposition", savedVisit.getVisitNumber());
                });

        // Release the patient from any bed they were placed in so the bed can be
        // cleaned and re-allocated. Bed goes to CLEANING (mandatory hygiene step).
        bedService.releaseVisitFromBed(savedVisit.getId(),
                "Disposition: " + request.getDispositionType());

        log.info("Visit {} disposition recorded: {} → status {}",
                savedVisit.getVisitNumber(), request.getDispositionType(), finalStatus);

        return VisitMapper.toResponse(savedVisit);
    }

    private VisitStatus mapDispositionToStatus(DispositionType disposition) {
        return switch (disposition) {
            case DISCHARGED_HOME -> VisitStatus.DISCHARGED;
            case ADMITTED_TO_WARD -> VisitStatus.ADMITTED;
            case ICU_ADMISSION -> VisitStatus.ICU_ADMITTED;
            case TRANSFERRED -> VisitStatus.TRANSFERRED;
            case LEFT_AGAINST_MEDICAL_ADVICE, LEFT_WITHOUT_BEING_SEEN -> VisitStatus.LEFT_WITHOUT_BEING_SEEN;
            case DECEASED -> VisitStatus.DECEASED;
        };
    }

    private String generateVisitNumber(String hospitalCode) {
        return nextVisitNumber(hospitalCode);
    }

    /**
     * Public visit-number generator. Used by other admission paths
     * (Direct Resus) that need to construct a {@link com.smartTriage.smartTriage_server.module.visit.entity.Visit}
     * directly while still drawing a unique visit number from the
     * shared in-memory counter.
     */
    public String nextVisitNumber(String hospitalCode) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long sequence = visitCounter.incrementAndGet();
        return String.format("V-%s-%s-%05d", hospitalCode, date, sequence);
    }
}
