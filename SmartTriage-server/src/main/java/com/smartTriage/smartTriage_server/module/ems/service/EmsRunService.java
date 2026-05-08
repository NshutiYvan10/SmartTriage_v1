package com.smartTriage.smartTriage_server.module.ems.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.ems.dto.*;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsIntervention;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.ems.mapper.EmsRunMapper;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsInterventionRepository;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.service.UnidentifiedPatientNameService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * EmsRunService — paramedic-side workflow.
 *
 * Lifecycle: DISPATCHED → EN_ROUTE → ARRIVED → HANDED_OFF (or
 * CANCELLED at any point). State-machine guards mirror the lab
 * module's pattern.
 *
 * Re-triage clock: when an arrival is confirmed, the visit's
 * edRetriageDueAt is set to (now + 15 min). The
 * {@link EmsRetriageMonitor} scheduler scans visits past this
 * deadline that still have no TriageRecord and fires
 * FIELD_TRIAGED_AWAITING_REVIEW alerts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmsRunService {

    private static final Duration ED_RETRIAGE_WINDOW = Duration.ofMinutes(15);

    private final EmsRunRepository emsRunRepository;
    private final EmsInterventionRepository interventionRepository;
    private final HospitalService hospitalService;
    private final VisitService visitService;
    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;
    private final UnidentifiedPatientNameService nameService;
    private final UserRepository userRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final RealTimeEventPublisher realTimeEventPublisher;

    // ====================================================================
    // CREATE / UPDATE
    // ====================================================================

    /** Paramedic starts a new run from the field. */
    @Transactional
    public EmsRunResponse createRun(CreateEmsRunRequest request) {
        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());
        User caller = currentUser().orElse(null);

        EmsRun run = EmsRun.builder()
                .hospital(hospital)
                .paramedic(caller)
                .paramedicName(request.getParamedicName() != null
                        ? request.getParamedicName()
                        : (caller != null ? caller.getFirstName() + " " + caller.getLastName() : null))
                .service(request.getService() != null ? request.getService() : EmsService.OTHER)
                .unitCallsign(request.getUnitCallsign())
                .dispatchedAt(Instant.now())
                .patientAgeYears(request.getPatientAgeYears())
                .patientSex(request.getPatientSex())
                .incidentLocation(request.getIncidentLocation())
                .mechanism(request.getMechanism())
                .historySummary(request.getHistorySummary())
                .status(EmsRunStatus.DISPATCHED)
                .build();
        run = emsRunRepository.save(run);

        log.info("[ems] Run {} dispatched by paramedic {} to hospital {}",
                run.getId(),
                run.getParamedicName(),
                hospital.getHospitalCode());

        return broadcastAndMap(run, false);
    }

    /** Paramedic updates run details (vitals, mechanism, field triage, etc.). */
    @Transactional
    public EmsRunResponse updateRun(UUID runId, UpdateEmsRunRequest req) {
        EmsRun run = findOrThrow(runId);
        ensureMutable(run, "update");

        if (req.getUnitCallsign() != null)        run.setUnitCallsign(req.getUnitCallsign());
        if (req.getParamedicName() != null)       run.setParamedicName(req.getParamedicName());
        if (req.getPatientAgeYears() != null)     run.setPatientAgeYears(req.getPatientAgeYears());
        if (req.getPatientSex() != null)          run.setPatientSex(req.getPatientSex());
        if (req.getIncidentLocation() != null)    run.setIncidentLocation(req.getIncidentLocation());
        if (req.getMechanism() != null)           run.setMechanism(req.getMechanism());
        if (req.getHistorySummary() != null)      run.setHistorySummary(req.getHistorySummary());
        if (req.getInjuriesObserved() != null)    run.setInjuriesObserved(req.getInjuriesObserved());
        if (req.getFieldTriageCategory() != null) run.setFieldTriageCategory(req.getFieldTriageCategory());
        if (req.getFieldTriageReason() != null)   run.setFieldTriageReason(req.getFieldTriageReason());
        if (req.getFieldGcs() != null)            run.setFieldGcs(req.getFieldGcs());
        if (req.getFieldRespRate() != null)       run.setFieldRespRate(req.getFieldRespRate());
        if (req.getFieldHr() != null)             run.setFieldHr(req.getFieldHr());
        if (req.getFieldSbp() != null)            run.setFieldSbp(req.getFieldSbp());
        if (req.getFieldDbp() != null)            run.setFieldDbp(req.getFieldDbp());
        if (req.getFieldSpo2() != null)           run.setFieldSpo2(req.getFieldSpo2());
        if (req.getFieldTemp() != null)           run.setFieldTemp(req.getFieldTemp());
        if (req.getFieldGlucose() != null)        run.setFieldGlucose(req.getFieldGlucose());
        if (req.getEtaMinutes() != null)          run.setEtaMinutes(req.getEtaMinutes());
        if (req.getNotes() != null)               run.setNotes(req.getNotes());

        run = emsRunRepository.save(run);

        // Mirror the field-triage call onto the visit if one is linked,
        // so dashboards can colour-code immediately.
        if (run.getVisit() != null && req.getFieldTriageCategory() != null) {
            run.getVisit().setFieldTriageCategory(req.getFieldTriageCategory());
            visitRepository.save(run.getVisit());
        }

        return broadcastAndMap(run, false);
    }

    @Transactional
    public EmsRunResponse cancelRun(UUID runId, String reason) {
        EmsRun run = findOrThrow(runId);
        if (run.getStatus() == EmsRunStatus.HANDED_OFF
                || run.getStatus() == EmsRunStatus.CANCELLED) {
            throw new ClinicalBusinessException(
                    "Cannot cancel run " + runId + " — already " + run.getStatus());
        }
        run.setStatus(EmsRunStatus.CANCELLED);
        run.setCancelledAt(Instant.now());
        run.setCancelReason(reason);
        run = emsRunRepository.save(run);
        log.info("[ems] Run {} cancelled — reason: {}", runId, reason);
        return broadcastAndMap(run, false);
    }

    // ====================================================================
    // INTERVENTIONS
    // ====================================================================

    @Transactional
    public EmsRunResponse addIntervention(UUID runId, AddInterventionRequest req) {
        EmsRun run = findOrThrow(runId);
        ensureMutable(run, "add intervention");

        EmsIntervention intervention = EmsIntervention.builder()
                .emsRun(run)
                .type(req.getType())
                .givenAt(req.getGivenAt() != null ? req.getGivenAt() : Instant.now())
                .givenByName(req.getGivenByName())
                .detail(req.getDetail())
                .dose(req.getDose())
                .route(req.getRoute())
                .outcome(req.getOutcome())
                .notes(req.getNotes())
                .build();
        interventionRepository.save(intervention);

        log.info("[ems] Intervention {} ({}) added to run {}", intervention.getType(),
                intervention.getDetail(), runId);

        return broadcastAndMap(run, true);
    }

    // ====================================================================
    // STATE TRANSITIONS
    // ====================================================================

    /**
     * Paramedic sends the pre-arrival ping. Creates / links the Visit
     * (placeholder if no patientId) and broadcasts EMS_PRE_ARRIVAL.
     * Status: DISPATCHED → EN_ROUTE.
     */
    @Transactional
    public EmsRunResponse preregister(UUID runId, PreregisterRequest req) {
        EmsRun run = findOrThrow(runId);
        if (run.getStatus() != EmsRunStatus.DISPATCHED && run.getStatus() != EmsRunStatus.EN_ROUTE) {
            throw new ClinicalBusinessException(
                    "Cannot preregister run " + runId + " from status " + run.getStatus());
        }

        Hospital hospital = run.getHospital();

        // Create or attach a Visit (idempotent on retries).
        if (run.getVisit() == null) {
            Patient patient;
            if (req != null && req.getPatientId() != null) {
                patient = patientRepository.findByIdAndIsActiveTrue(req.getPatientId())
                        .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", req.getPatientId()));
            } else {
                UnidentifiedPatientNameService.PlaceholderLabel claimed = nameService.claimNext(hospital.getId());
                patient = Patient.builder()
                        .firstName("Unknown")
                        .lastName(claimed.label())
                        .hospital(hospital)
                        .isUnidentified(true)
                        .placeholderLabel(claimed.label())
                        .placeholderAssignedAt(Instant.now())
                        .build();
                patient = patientRepository.save(patient);
                log.info("[ems] Created placeholder patient '{}' for run {}", claimed.label(), runId);
            }

            Instant now = Instant.now();
            String chief = run.getMechanism() != null
                    ? "EMS pre-arrival: " + run.getMechanism()
                    : "EMS pre-arrival";
            Visit visit = Visit.builder()
                    .patient(patient)
                    .hospital(hospital)
                    .visitNumber(visitService.nextVisitNumber(hospital.getHospitalCode()))
                    .arrivalMode(ArrivalMode.AMBULANCE)
                    .arrivalTime(now)
                    .arrivalConfirmedAt(null)         // door clock starts at confirmArrival
                    .ambulancePreArrival(true)
                    .chiefComplaint(chief)
                    .status(VisitStatus.REGISTERED)
                    .emsRunId(run.getId())
                    .fieldTriageCategory(run.getFieldTriageCategory())
                    .build();
            visit = visitRepository.save(visit);
            run.setVisit(visit);
        }

        if (req != null && req.getEtaMinutes() != null) {
            run.setEtaMinutes(req.getEtaMinutes());
        }
        run.setStatus(EmsRunStatus.EN_ROUTE);
        run = emsRunRepository.save(run);

        // Pre-arrival alert to the receiving ED.
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(run.getVisit())
                .alertType(AlertType.EMS_PRE_ARRIVAL)
                .severity(severityFor(run.getFieldTriageCategory()))
                .title("Ambulance inbound: " + safe(run.getMechanism(), "patient"))
                .message(buildPreArrivalMessage(run, req))
                .autoGenerated(true)
                .build();
        clinicalAlertRepository.save(alert);

        log.info("[ems] Run {} EN_ROUTE — visit {} pre-registered", runId, run.getVisit().getId());

        return broadcastAndMap(run, true);
    }

    /**
     * Paramedic / nurse marks the patient as physically arrived.
     * Status: EN_ROUTE → ARRIVED. Sets the visit's arrivalConfirmedAt
     * and edRetriageDueAt.
     */
    @Transactional
    public EmsRunResponse confirmArrival(UUID runId) {
        EmsRun run = findOrThrow(runId);
        if (run.getStatus() != EmsRunStatus.EN_ROUTE && run.getStatus() != EmsRunStatus.DISPATCHED) {
            throw new ClinicalBusinessException(
                    "Cannot confirm arrival for run " + runId + " from status " + run.getStatus());
        }
        if (run.getVisit() == null) {
            throw new ClinicalBusinessException(
                    "Run " + runId + " has no linked visit — call preregister first");
        }

        Instant now = Instant.now();
        run.setStatus(EmsRunStatus.ARRIVED);
        run.setEdArrivedAt(now);

        Visit v = run.getVisit();
        v.setArrivalConfirmedAt(now);
        v.setEdRetriageDueAt(now.plus(ED_RETRIAGE_WINDOW));
        visitRepository.save(v);

        run = emsRunRepository.save(run);
        log.info("[ems] Run {} ARRIVED — visit {} re-triage due at {}",
                runId, v.getId(), v.getEdRetriageDueAt());

        return broadcastAndMap(run, true);
    }

    /**
     * Receiving nurse acknowledges handover.
     * Status: ARRIVED → HANDED_OFF.
     *
     * Defensive: paramedic cannot ack their own handover (the
     * controller layer enforces RBAC, but we double-check role here).
     */
    @Transactional
    public EmsRunResponse transferOfCare(UUID runId, TransferOfCareRequest req) {
        EmsRun run = findOrThrow(runId);
        if (run.getStatus() != EmsRunStatus.ARRIVED) {
            throw new ClinicalBusinessException(
                    "Cannot transfer care for run " + runId + " from status " + run.getStatus());
        }

        User caller = currentUser().orElse(null);
        if (caller != null && caller.getRole() == Role.PARAMEDIC) {
            throw new ClinicalBusinessException(
                    "A paramedic cannot acknowledge their own handover. Ask the receiving nurse or doctor to confirm.");
        }

        Instant now = Instant.now();
        run.setStatus(EmsRunStatus.HANDED_OFF);
        run.setHandedOffAt(now);
        run.setHandedOffTo(caller);
        if (req != null) {
            run.setHandedOffToName(req.getReceivedByName() != null
                    ? req.getReceivedByName()
                    : (caller != null ? caller.getFirstName() + " " + caller.getLastName() : null));
            run.setHandoverAcknowledgementText(req.getAcknowledgementText());
        }

        run = emsRunRepository.save(run);
        log.info("[ems] Run {} HANDED_OFF to {}", runId, run.getHandedOffToName());

        return broadcastAndMap(run, true);
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    public EmsRunResponse getById(UUID runId) {
        EmsRun run = findOrThrow(runId);
        List<EmsIntervention> ivs = interventionRepository
                .findByEmsRunIdAndIsActiveTrueOrderByGivenAtAsc(runId);
        return EmsRunMapper.toResponse(run, ivs);
    }

    public List<EmsRunResponse> getInbound(UUID hospitalId) {
        return emsRunRepository.findInbound(hospitalId)
                .stream().map(EmsRunMapper::toResponse).collect(Collectors.toList());
    }

    public List<EmsRunResponse> getMyRuns() {
        User caller = currentUser().orElse(null);
        if (caller == null) return List.of();
        return emsRunRepository.findByParamedic(caller.getId())
                .stream().map(EmsRunMapper::toResponse).collect(Collectors.toList());
    }

    public Optional<EmsRunResponse> getByVisitId(UUID visitId) {
        return emsRunRepository.findByVisitIdAndIsActiveTrue(visitId)
                .map(r -> EmsRunMapper.toResponse(r,
                        interventionRepository.findByEmsRunIdAndIsActiveTrueOrderByGivenAtAsc(r.getId())));
    }

    // ====================================================================
    // HELPERS
    // ====================================================================

    private EmsRun findOrThrow(UUID runId) {
        return emsRunRepository.findByIdAndIsActiveTrue(runId)
                .orElseThrow(() -> new ResourceNotFoundException("EmsRun", "id", runId));
    }

    private void ensureMutable(EmsRun run, String action) {
        if (run.getStatus() == EmsRunStatus.HANDED_OFF
                || run.getStatus() == EmsRunStatus.CANCELLED) {
            throw new ClinicalBusinessException(
                    "Cannot " + action + " on run " + run.getId() + " — status is " + run.getStatus());
        }
    }

    private EmsRunResponse broadcastAndMap(EmsRun run, boolean withInterventions) {
        List<EmsIntervention> ivs = withInterventions
                ? interventionRepository.findByEmsRunIdAndIsActiveTrueOrderByGivenAtAsc(run.getId())
                : null;
        EmsRunResponse response = EmsRunMapper.toResponse(run, ivs);
        try {
            realTimeEventPublisher.publishEmsRun(run.getHospital().getId(), response);
        } catch (Exception e) {
            log.warn("[ems] Broadcast failed for run {}: {}", run.getId(), e.getMessage());
        }
        return response;
    }

    private Optional<User> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Optional.empty();
        Object p = auth.getPrincipal();
        if (p instanceof User u) return Optional.of(u);
        return Optional.empty();
    }

    private static AlertSeverity severityFor(String fieldTriageCategory) {
        if (fieldTriageCategory == null) return AlertSeverity.MEDIUM;
        return switch (fieldTriageCategory.toUpperCase()) {
            case "RED"    -> AlertSeverity.CRITICAL;
            case "ORANGE" -> AlertSeverity.HIGH;
            case "YELLOW" -> AlertSeverity.MEDIUM;
            default       -> AlertSeverity.LOW;
        };
    }

    private static String buildPreArrivalMessage(EmsRun run, PreregisterRequest req) {
        StringBuilder s = new StringBuilder();
        s.append("Inbound: ").append(safe(run.getMechanism(), "patient"));
        if (run.getFieldTriageCategory() != null) {
            s.append(" • field triage ").append(run.getFieldTriageCategory());
        }
        if (req != null && req.getEtaMinutes() != null) {
            s.append(" • ETA ").append(req.getEtaMinutes()).append(" min");
        }
        if (run.getFieldGcs() != null) s.append(" • GCS ").append(run.getFieldGcs());
        if (run.getFieldSbp() != null && run.getFieldDbp() != null) {
            s.append(" • BP ").append(run.getFieldSbp()).append("/").append(run.getFieldDbp());
        }
        if (run.getFieldSpo2() != null) s.append(" • SpO2 ").append(run.getFieldSpo2()).append("%");
        if (req != null && req.getPreArrivalNote() != null && !req.getPreArrivalNote().isBlank()) {
            s.append(" — ").append(req.getPreArrivalNote());
        }
        return s.toString();
    }

    private static String safe(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
