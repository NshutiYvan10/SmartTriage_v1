package com.smartTriage.smartTriage_server.module.visit.service;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.enums.DispositionType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
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
@Service("visitService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisitService {

    private final VisitRepository visitRepository;
    /** Restart-proof, DB-backed per-(hospital,day) visit-number sequence (replaces the old in-memory counter). */
    private final com.smartTriage.smartTriage_server.module.visit.repository.VisitSequenceCounterRepository visitSequenceCounterRepository;
    private final PatientService patientService;
    private final HospitalService hospitalService;
    private final DeviceSessionRepository deviceSessionRepository;
    private final BedService bedService;
    private final com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService shiftAssignmentService;
    private final com.smartTriage.smartTriage_server.security.ClinicalAuthz clinicalAuthz;
    private final com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository medicationAdministrationRepository;
    private final com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository investigationRepository;
    private final com.smartTriage.smartTriage_server.module.icu.repository.IcuEscalationRepository icuEscalationRepository;
    /** B4 — pushes a visit event after commit so dashboards refresh live when a
     *  returning patient is admitted to a new visit. */
    private final com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher realTimeEventPublisher;
    /** #7 — used to require a real discharge summary document before a discharge
     *  disposition is recorded (no cycle: this is the repository, not the service). */
    private final com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository clinicalDocumentRepository;

    /** Defensive cap on the collision-skip loop in {@link #nextVisitNumber} — far above
     *  any realistic same-day visit count; prevents a pathological infinite loop. */
    private static final int VISIT_NUMBER_MAX_ATTEMPTS = 10_000;

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

        // B4 — notify dashboards of the new admission so they refresh live.
        realTimeEventPublisher.publishVisitEventAfterCommit(
                hospital.getId(),
                java.util.Map.of(
                        "type", "CREATED",
                        "visitId", visit.getId().toString(),
                        "hospitalId", hospital.getId().toString()));

        return VisitMapper.toResponse(visit);
    }

    public VisitResponse getVisitById(UUID id) {
        Visit visit = findVisitOrThrow(id);
        return VisitMapper.toResponse(visit);
    }

    public Page<VisitResponse> getActiveVisits(UUID hospitalId, Pageable pageable) {
        Page<VisitResponse> page = visitRepository.findActiveVisits(hospitalId, pageable)
                .map(VisitMapper::toResponse);
        enrichWithHandoverSignals(page.getContent());
        return page;
    }

    /**
     * The "placed but not yet formally triaged" worklist — patients routed straight into a
     * treatment zone (acuity-split RED/ORANGE ambulance arrivals, Direct Resus) who bypass the
     * pre-triage desk queue yet still owe a formal ED triage. Returned hospital-wide (no per-zone
     * scoping): the endpoint is gated to the triage authorities (triage nurse / charge nurse /
     * shift-lead), who coordinate the whole floor and are the ones who file the triage.
     */
    public Page<VisitResponse> getPlacedAwaitingEdTriage(UUID hospitalId, Pageable pageable) {
        return visitRepository.findPlacedAwaitingEdTriage(hospitalId, pageable)
                .map(VisitMapper::toResponse);
    }

    /**
     * Populate the shift-handoff aggregate fields on a list of
     * {@link VisitResponse} rows in three batched queries:
     * pending-meds, pending+critical-resulted labs, open ICU
     * escalations. Idempotent and safe on empty input. Mutates the
     * passed-in objects rather than returning new ones — callers
     * that already have a {@code Page<VisitResponse>} keep their
     * pagination metadata.
     *
     * <p>Not called on single-record reads (visit-by-id) because the
     * detail page already loads each underlying collection in full;
     * computing aggregate counts there would be wasted effort.
     */
    private void enrichWithHandoverSignals(java.util.List<VisitResponse> responses) {
        if (responses == null || responses.isEmpty()) return;
        java.util.List<UUID> visitIds = responses.stream()
                .map(VisitResponse::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (visitIds.isEmpty()) return;

        java.util.Map<UUID, Long> pendingMeds = toMap(
                medicationAdministrationRepository.countPendingByVisitIds(visitIds));
        java.util.Map<UUID, Long> pendingLabs = toMap(
                investigationRepository.countPendingByVisitIds(visitIds));
        java.util.Map<UUID, Long> criticalResults = toMap(
                investigationRepository.countCriticalResultedByVisitIds(visitIds));
        java.util.Set<UUID> openEscalations = new java.util.HashSet<>(
                icuEscalationRepository.findVisitIdsWithOpenEscalation(visitIds));

        for (VisitResponse r : responses) {
            UUID id = r.getId();
            r.setPendingMedicationsCount(pendingMeds.getOrDefault(id, 0L).intValue());
            r.setPendingInvestigationsCount(pendingLabs.getOrDefault(id, 0L).intValue());
            r.setUnacknowledgedCriticalResultsCount(criticalResults.getOrDefault(id, 0L).intValue());
            r.setHasOpenIcuEscalation(openEscalations.contains(id));
        }
    }

    private static java.util.Map<UUID, Long> toMap(java.util.List<Object[]> rows) {
        java.util.Map<UUID, Long> out = new java.util.HashMap<>();
        for (Object[] r : rows) {
            if (r != null && r.length >= 2 && r[0] instanceof UUID id && r[1] instanceof Number n) {
                out.put(id, n.longValue());
            }
        }
        return out;
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
        List<VisitResponse> rows = visitRepository.findActiveVisitsInZones(
                hospitalId, java.util.List.of(zone),
                org.springframework.data.domain.PageRequest.of(0, 200))
                .stream()
                .map(VisitMapper::toResponse)
                .collect(Collectors.toList());
        enrichWithHandoverSignals(rows);
        return rows;
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
        Page<VisitResponse> page = visitRepository.findActiveVisitsInZones(hospitalId, zones, pageable)
                .map(VisitMapper::toResponse);
        enrichWithHandoverSignals(page.getContent());
        return page;
    }

    /**
     * Caller-aware active visit list. Routes cross-zone actors
     * (admins, shift-lead, Charge Nurse) through the full hospital
     * roster; everyone else gets only their assigned zone.
     *
     * <p>An off-shift clinician without a zone assignment gets an empty
     * page — not an error. Frontend renders that as "you're not on shift,
     * no patients to monitor" so the user has a clear next action
     * (pick up a shift) instead of a blank dashboard with a 403.
     */
    public Page<VisitResponse> getActiveVisitsForCaller(
            UUID hospitalId,
            org.springframework.security.core.Authentication authentication,
            Pageable pageable) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof com.smartTriage.smartTriage_server.module.user.entity.User user)) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        com.smartTriage.smartTriage_server.common.enums.Role role = user.getRole();

        // RBAC fix — admins do not appear in clinical queues. They have
        // their own admin views; this endpoint is for clinical staff.
        if (role == com.smartTriage.smartTriage_server.common.enums.Role.SUPER_ADMIN
                || role == com.smartTriage.smartTriage_server.common.enums.Role.HOSPITAL_ADMIN) {
            return org.springframework.data.domain.Page.empty(pageable);
        }

        // 1. Cross-zone clinical authorities — Charge Nurse designation
        //    or current shift-lead badge. Full hospital active list.
        //    (Admins are already excluded above.)
        if (clinicalAuthz.canSeeAllZonesAtHospital(authentication, hospitalId)) {
            return getActiveVisits(hospitalId, pageable);
        }

        // 2. RBAC fix — today's TRIAGE_NURSE gets the pre-triage queue.
        //    Previously they got an empty page because the zone filter
        //    asks for currentEdZone IN [TRIAGE] but pre-triage rows have
        //    currentEdZone IS NULL. They now see exactly what they
        //    should: patients awaiting triage assignment.
        if (clinicalAuthz.callerIsTodaysTriageNurse(authentication)) {
            return visitRepository
                    .findPreTriageActiveVisits(hospitalId, pageable)
                    .map(VisitMapper::toResponse);
        }

        // 3. Non-zone-bound operational roles — REGISTRAR (front
        //    desk: needs the active queue to answer "where is patient
        //    X / has the family arrived"), LAB_TECHNICIAN (needs to
        //    look up the patient associated with a specimen),
        //    READ_ONLY (governance audit). None of these roles take a
        //    zone shift, so they were previously falling through the
        //    zone-resolution branch and getting an empty page — that
        //    was the "Registrar registered a patient but couldn't see
        //    them in the list" bug.
        //
        //    PARAMEDIC is deliberately EXCLUDED: a paramedic's patients
        //    are the ones THEY transported (their own EMS runs, served by
        //    getMyRuns), NOT the hospital-wide active roster. Letting them
        //    read every active patient here was a PHI leak. Removed, they
        //    fall through to the zone branch below and — having no shift —
        //    get an empty page, which is correct.
        //
        //    They still must belong to this hospital — the controller
        //    enforces that with @PreAuthorize canAccessHospital.
        if (role == com.smartTriage.smartTriage_server.common.enums.Role.REGISTRAR
                || role == com.smartTriage.smartTriage_server.common.enums.Role.LAB_TECHNICIAN
                || role == com.smartTriage.smartTriage_server.common.enums.Role.READ_ONLY) {
            return getActiveVisits(hospitalId, pageable);
        }

        // 4. Zone-bound clinical roles (DOCTOR, NURSE) — patients in
        //    EVERY zone the caller is covering on their current shift.
        //    Workflow 4: primary zone + any additionalZones from the
        //    shift assignment, unioned into the existing multi-zone
        //    query. Off-shift returns empty by design: the frontend
        //    renders this as "you're not on shift", which is the
        //    correct cue rather than leaking other zones' data.
        return shiftAssignmentService
                .getCurrentShiftForUser(user.getId())
                .map(sa -> {
                    java.util.Set<com.smartTriage.smartTriage_server.common.enums.EdZone> zones =
                            java.util.EnumSet.of(sa.getZone());
                    if (sa.getAdditionalZones() != null && !sa.getAdditionalZones().isEmpty()) {
                        zones.addAll(sa.getAdditionalZones());
                    }
                    return zones;
                })
                .map(zones -> visitRepository
                        .findActiveVisitsInZones(hospitalId, zones, pageable)
                        .map(VisitMapper::toResponse))
                .orElseGet(() -> org.springframework.data.domain.Page.empty(pageable));
    }

    /**
     * SpEL helper used by {@code @PreAuthorize} on
     * {@code GET /visits/hospital/{hospitalId}/zone/{zone}} — true when
     * the caller's active shift assignment is on the requested zone.
     * Lets a doctor view their own zone's roster without granting
     * cross-zone access. Returns false for off-shift callers.
     */
    public boolean callerIsAssignedToZone(
            org.springframework.security.core.Authentication authentication,
            UUID hospitalId,
            EdZone zone) {
        try {
            if (authentication == null || hospitalId == null || zone == null) {
                return false;
            }
            Object principal = authentication.getPrincipal();
            if (!(principal instanceof com.smartTriage.smartTriage_server.module.user.entity.User user)) {
                return false;
            }
            return shiftAssignmentService
                    .getCurrentShiftForUser(user.getId())
                    .map(sa -> {
                        // Workflow 4 — caller is assigned to the zone
                        // if it matches the primary OR is in their
                        // additional-coverage set.
                        if (!hospitalId.equals(sa.getHospitalId())) return false;
                        if (zone.equals(sa.getZone())) return true;
                        return sa.getAdditionalZones() != null
                                && sa.getAdditionalZones().contains(zone);
                    })
                    .orElse(false);
        } catch (Exception e) {
            log.error("callerIsAssignedToZone error: {}", e.getMessage(), e);
            return false;
        }
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

        // #7 — a discharge home must be backed by a real discharge summary document,
        // not just a disposition flag. Require one to EXIST before recording the
        // discharge (generating it is a one-click, attributable action; this checks
        // existence only — it never blocks on a pending signature, so a patient is
        // never trapped over paperwork).
        if (request.getDispositionType() == DispositionType.DISCHARGED_HOME
                && !clinicalDocumentRepository.existsByVisitIdAndDocumentTypeAndIsActiveTrue(
                        visitId, ClinicalDocumentType.DISCHARGE_SUMMARY)) {
            throw new ClinicalBusinessException(
                    "A discharge summary is required before discharging this patient home. "
                    + "Generate (and sign) the discharge summary first.");
        }

        // Set disposition fields
        visit.setDispositionType(request.getDispositionType());
        visit.setDispositionTime(Instant.now());
        visit.setDispositionNotes(request.getNotes());
        // #7 data-loss fix — persist the destination (previously silently discarded).
        visit.setDispositionDestinationWard(request.getDestinationWard());
        visit.setDispositionReceivingFacility(request.getReceivingFacility());

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
    @Transactional
    public String nextVisitNumber(String hospitalCode) {
        LocalDate today = LocalDate.now();
        String date = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // Draw the next sequence from the DURABLE, atomic per-(hospital,day) DB
        // counter — survives restarts and serialises concurrent registrations, so
        // it cannot re-issue an existing number the way the old in-memory AtomicLong
        // did (the post-restart 409 "conflicts with existing data"). The defensive
        // loop only fires if a legacy/leftover number already sits where the counter
        // lands (e.g. the very first generation after this fix deploys); it claims
        // again — the counter is monotonic, so it converges in at most a handful of
        // tries and never collides thereafter.
        for (int attempt = 0; attempt < VISIT_NUMBER_MAX_ATTEMPTS; attempt++) {
            long sequence = visitSequenceCounterRepository.claimNext(hospitalCode, today);
            String candidate = String.format("V-%s-%s-%05d", hospitalCode, date, sequence);
            if (!visitRepository.existsByVisitNumber(candidate)) {
                return candidate;
            }
            log.warn("[visit] Visit number {} already exists — advancing the sequence (attempt {}).",
                    candidate, attempt + 1);
        }
        // Unreachable in practice; fail loud rather than silently mint a dup.
        throw new IllegalStateException(
                "Could not allocate a unique visit number for hospital " + hospitalCode
                        + " after " + VISIT_NUMBER_MAX_ATTEMPTS + " attempts.");
    }
}
