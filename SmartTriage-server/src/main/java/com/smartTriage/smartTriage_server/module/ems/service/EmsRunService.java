package com.smartTriage.smartTriage_server.module.ems.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.ems.dto.*;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsIntervention;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.ems.mapper.EmsRunMapper;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsInterventionRepository;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.service.UnidentifiedPatientNameService;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.triage.dto.PerformTriageRequest;
import com.smartTriage.smartTriage_server.module.triage.engine.PediatricTewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.engine.RwandaPediatricTriageDecisionEngine;
import com.smartTriage.smartTriage_server.module.triage.engine.RwandaTriageDecisionEngine;
import com.smartTriage.smartTriage_server.module.triage.engine.TewsCalculator;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.module.visit.service.ZoneRoutingService;
import com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import com.smartTriage.smartTriage_server.security.ClinicalAuthz;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    /** Tighter re-triage fuse for a field-RED / lights arrival — they can't wait 15 min at the door. */
    private static final Duration ED_RETRIAGE_WINDOW_RED = Duration.ofMinutes(5);

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
    private final ClinicalAuthz clinicalAuthz;
    private final HospitalRepository hospitalRepository;
    private final ShiftAssignmentService shiftAssignmentService;

    // Shared triage engines — the SAME ones the in-hospital triage uses, so a
    // paramedic's field call is computed identically to the ED's.
    private final TewsCalculator tewsCalculator;
    private final PediatricTewsCalculator pediatricTewsCalculator;
    private final RwandaTriageDecisionEngine decisionEngine;
    private final RwandaPediatricTriageDecisionEngine pediatricDecisionEngine;
    private final EmsPcrPdfService emsPcrPdfService;
    private final ZoneRoutingService zoneRoutingService;
    private final ClinicalNoteRepository clinicalNoteRepository;

    /** Patients younger than this use the KFH pediatric form/engine. */
    private static final int PEDIATRIC_AGE_CEILING_YEARS = 13;

    /** Local mapper for persisting the field-triage input as JSON (mirrors OfflineSyncService's pattern). */
    private static final com.fasterxml.jackson.databind.ObjectMapper FIELD_TRIAGE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
        // NOTE: fieldTriageCategory is intentionally NOT settable here — the
        // field category is ALWAYS engine-computed via computeFieldTriage so it
        // carries a TEWS + decision-path audit trail and is concordant with the
        // ED. A free-text category set via this PATCH would bypass that.
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
        // The field category/visit-mirror is handled only by computeFieldTriage
        // (engine-computed), so updateRun no longer touches it.
        return broadcastAndMap(run, false);
    }

    /**
     * Compute the paramedic's field triage with the SAME engine the ED
     * uses. We map the field vitals + discriminators onto a real
     * {@link PerformTriageRequest} and run {@link RwandaTriageDecisionEngine}
     * (adult) or {@link RwandaPediatricTriageDecisionEngine} (KFH peds,
     * age &lt;13) — identical TEWS thresholds, identical RED/ORANGE/…
     * flowchart — so the field category is concordant with the in-hospital
     * call rather than a subjective pick. The engine's TEWS, category and
     * decision-path audit string are persisted on the run.
     *
     * <p>The ED still performs the authoritative full-form re-triage on
     * arrival (the {@link EmsRetriageMonitor} enforces that); this gives the
     * receiving team a trustworthy, computed pre-arrival severity.
     */
    @Transactional
    public EmsRunResponse computeFieldTriage(UUID runId, FieldTriageRequest req) {
        EmsRun run = findOrThrow(runId);
        ensureMutable(run, "compute field triage");

        boolean isChild = req.getIsChild() != null
                ? req.getIsChild()
                : (run.getPatientAgeYears() != null
                        && run.getPatientAgeYears() < PEDIATRIC_AGE_CEILING_YEARS);

        int ageInMonths = run.getPatientAgeYears() != null
                ? Math.max(0, run.getPatientAgeYears() * 12)
                : PediatricTewsCalculator.INFANT_AGE_BOUNDARY_MONTHS;

        // Transient VitalSigns — fed to the calculator only (never persisted;
        // the run carries its own field-vitals columns).
        VitalSigns vitals = VitalSigns.builder()
                .respiratoryRate(req.getRespiratoryRate())
                .heartRate(req.getHeartRate())
                .systolicBp(req.getSystolicBp())
                .diastolicBp(req.getDiastolicBp())
                .temperature(req.getTemperature())
                .spo2(req.getSpo2())
                .painScore(req.getPainScore())
                .bloodGlucose(req.getBloodGlucose())
                .build();

        PerformTriageRequest ptr = PerformTriageRequest.builder()
                .respiratoryRate(req.getRespiratoryRate())
                .heartRate(req.getHeartRate())
                .systolicBP(req.getSystolicBp())
                .temperature(req.getTemperature())
                .spo2(req.getSpo2())
                .diastolicBp(req.getDiastolicBp())
                .bloodGlucose(req.getBloodGlucose())
                .painScore(req.getPainScore())
                .mobility(req.getMobility())
                .avpu(req.getAvpu())
                .traumaStatus(req.getTraumaStatus())
                // Emergency signs (shared)
                .hasAirwayCompromise(req.isHasAirwayCompromise())
                .hasSevereRespiratoryDistress(req.isHasSevereRespiratoryDistress())
                .hasCardiacArrest(req.isHasCardiacArrest())
                .hasUncontrolledHaemorrhage(req.isHasUncontrolledHaemorrhage())
                .hasStabGunWoundNeckChest(req.isHasStabGunWoundNeckChest())
                .hasConvulsions(req.isHasConvulsions())
                // A recorded field GCS was persisted but never reached the engine
                // (PerformTriageRequest has no GCS field), so a GCS of 6 without a
                // separately-ticked Coma box was silently under-triaged. GCS <= 8 is
                // the established coma / intubation threshold — feed it into the coma
                // emergency sign so a low GCS alone forces RED.
                .hasComa(req.isHasComa() || (req.getGcs() != null && req.getGcs() <= 8))
                .hasHypoglycaemia(req.isHasHypoglycaemia())
                .hasBurnFaceInhalation(req.isHasBurnFaceInhalation())
                // Pediatric-only emergency signs (defensive: only when child)
                .childCentralCyanosis(isChild && req.isChildCentralCyanosis())
                .childPulseLowOrAbsent(isChild && req.isChildPulseLowOrAbsent())
                // Very urgent (focused)
                .vuAlteredMentalStatus(req.isVuAlteredMentalStatus())
                .vuFocalNeurologicDeficit(req.isVuFocalNeurologicDeficit())
                .vuChestPain(req.isVuChestPain())
                .vuShortnessOfBreath(req.isVuShortnessOfBreath())
                .vuPoisoningOverdose(req.isVuPoisoningOverdose())
                .vuCoughingVomitingBlood(req.isVuCoughingVomitingBlood())
                .vuSevereMechanismOfInjury(req.isVuSevereMechanismOfInjury())
                .vuOpenFracture(req.isVuOpenFracture())
                .vuThreatenedLimb(req.isVuThreatenedLimb())
                .vuVerySeverePain(req.isVuVerySeverePain())
                .vuBurnOver20Percent(req.isVuBurnOver20Percent())
                // Urgent (focused)
                .urgAbdominalPain(req.isUrgAbdominalPain())
                .urgModeratePain(req.isUrgModeratePain())
                .urgClosedFracture(req.isUrgClosedFracture())
                .urgLacerationAbscess(req.isUrgLacerationAbscess())
                .urgVeryPale(req.isUrgVeryPale())
                .urgUnableToDrinkVomits(req.isUrgUnableToDrinkVomits())
                .build();

        int tews = isChild
                ? pediatricTewsCalculator.calculatePediatricTewsScore(
                        ageInMonths, vitals, req.getMobility(), req.getAvpu(), req.getTraumaStatus())
                : tewsCalculator.calculateTewsScore(
                        vitals, req.getMobility(), req.getAvpu(), req.getTraumaStatus());

        TriageCategory category;
        String decisionPath;
        if (isChild) {
            var d = pediatricDecisionEngine.decide(ageInMonths, tews, req.getSpo2(), req.getPainScore(), ptr);
            category = d.category();
            decisionPath = d.decisionPath();
        } else {
            var d = decisionEngine.decide(tews, req.getSpo2(), req.getPainScore(), ptr);
            category = d.category();
            decisionPath = d.decisionPath();
        }

        // Silent-downgrade guard: a re-compute must not LOWER acuity below a
        // previously computed category unless the paramedic explicitly
        // acknowledges it. Protects against re-opening an en-route run and
        // recomputing a falsely-lower category.
        String previousCategory = run.getFieldTriageCategory();
        if (previousCategory != null && !req.isAcknowledgeDowngrade()) {
            try {
                TriageCategory prev = TriageCategory.valueOf(previousCategory);
                if (category.getSeverity() < prev.getSeverity()) {
                    throw new ClinicalBusinessException(
                            "Re-computed field triage " + category + " is LOWER acuity than the current "
                                    + prev + " for run " + runId + ". Re-confirm to record a downgrade.");
                }
            } catch (IllegalArgumentException ignore) {
                // previous was a legacy free-text value — no severity to compare.
            }
        }

        // Persist the field vitals snapshot (null-guarded — a partial submit
        // never blanks a value already on file).
        if (req.getRespiratoryRate() != null) run.setFieldRespRate(req.getRespiratoryRate());
        if (req.getHeartRate() != null)       run.setFieldHr(req.getHeartRate());
        if (req.getSystolicBp() != null)       run.setFieldSbp(req.getSystolicBp());
        if (req.getDiastolicBp() != null)      run.setFieldDbp(req.getDiastolicBp());
        if (req.getSpo2() != null)             run.setFieldSpo2(req.getSpo2());
        if (req.getGcs() != null)              run.setFieldGcs(req.getGcs());
        if (req.getTemperature() != null)      run.setFieldTemp(BigDecimal.valueOf(req.getTemperature()));
        if (req.getBloodGlucose() != null)     run.setFieldGlucose(BigDecimal.valueOf(req.getBloodGlucose()));

        run.setFieldTriageCategory(category.name());
        run.setFieldTewsScore(tews);
        run.setFieldTriageDecisionPath(decisionPath);
        run.setFieldTriageIsChild(isChild);
        if (req.getReason() != null && !req.getReason().isBlank()) {
            run.setFieldTriageReason(req.getReason());
        }
        // Persist the raw inputs so a re-open rehydrates the exact assessment.
        try {
            run.setFieldTriageInput(FIELD_TRIAGE_MAPPER.writeValueAsString(req));
        } catch (Exception e) {
            log.warn("[ems] Could not serialise field-triage input for run {}: {}", runId, e.getMessage());
        }
        run = emsRunRepository.save(run);

        // Keep a linked visit's denormalised field category in step.
        if (run.getVisit() != null) {
            run.getVisit().setFieldTriageCategory(category.name());
            visitRepository.save(run.getVisit());
        }

        log.info("[ems] Run {} field triage computed: {} (TEWS {}, {} engine) — {}",
                runId, category, tews, isChild ? "KFH peds" : "adult", decisionPath);

        // A recompute that upgrades an already-pinged inbound run to RED must
        // re-escalate the ED pre-arrival to CRITICAL/RESUS (no-op otherwise).
        escalatePreArrivalIfNowCritical(run, "field re-triage to " + category.name());

        return broadcastAndMap(run, false);
    }

    /**
     * Toggle the blue-light / priority-transport flag. An active lights run
     * escalates the pre-arrival alert to CRITICAL severity and routes it to
     * the RESUS zone (see {@link #preregister}).
     */
    @Transactional
    public EmsRunResponse setLights(UUID runId, boolean active) {
        EmsRun run = findOrThrow(runId);
        ensureMutable(run, "toggle lights");
        run.setLightsActive(active);
        run.setLightsActivatedAt(active ? Instant.now() : null);
        run = emsRunRepository.save(run);
        log.info("[ems] Run {} lights {}", runId, active ? "ACTIVATED" : "cleared");
        // Lights switched on AFTER the pre-arrival ping must re-escalate the ED
        // alert to CRITICAL/RESUS (never-downgrade: clearing lights does not).
        if (active) escalatePreArrivalIfNowCritical(run, "blue lights activated");
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

        // Pre-arrival alert to the receiving ED — live-routed so the charge
        // nurse and (for RED / lights) the RESUS zone are notified at once,
        // not merely persisted for whoever happens to open the alert centre.
        boolean critical = isRedField(run.getFieldTriageCategory()) || run.isLightsActive();
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(run.getVisit())
                .alertType(AlertType.EMS_PRE_ARRIVAL)
                .severity(critical ? AlertSeverity.CRITICAL : severityFor(run.getFieldTriageCategory()))
                .title((critical ? "INCOMING CRITICAL — " : "Ambulance inbound: ")
                        + safe(run.getMechanism(), "patient"))
                .message(buildPreArrivalMessage(run, req))
                .targetZone(preArrivalTargetZone(run, critical))
                .escalationTier(1)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);
        routePreArrivalAlert(run, alert, critical);

        log.info("[ems] Run {} EN_ROUTE — visit {} pre-registered (critical={})",
                runId, run.getVisit().getId(), critical);

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
        // Only an EN_ROUTE run may be marked ARRIVED. A DISPATCHED run has no
        // linked visit yet, so allowing it here only produced a confusing
        // "no linked visit" error — reject it cleanly with the real next step.
        if (run.getStatus() != EmsRunStatus.EN_ROUTE) {
            throw new ClinicalBusinessException(
                    run.getStatus() == EmsRunStatus.DISPATCHED
                            ? "Send the pre-arrival to the ED first (run " + runId + " is still DISPATCHED)."
                            : "Cannot confirm arrival for run " + runId + " from status " + run.getStatus());
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
        // RED / lights arrivals get a tighter ED re-triage deadline — a
        // field-critical patient at the door must not sit 15 min un-triaged.
        boolean redOrLights = isRedField(run.getFieldTriageCategory()) || run.isLightsActive();
        v.setEdRetriageDueAt(now.plus(redOrLights ? ED_RETRIAGE_WINDOW_RED : ED_RETRIAGE_WINDOW));
        // B11 — advance REGISTERED → AWAITING_TRIAGE on physical arrival so the
        // triage queue/board reflects "arrived, awaiting triage" rather than the
        // pre-arrival REGISTERED state. Guarded to REGISTERED so a visit that has
        // already been triaged (or progressed further) is never moved backward.
        if (v.getStatus() == VisitStatus.REGISTERED) {
            v.setStatus(VisitStatus.AWAITING_TRIAGE);
        }
        // ACUITY-SPLIT placement from the paramedic's field triage. The field call
        // stands as the ADVISORY working category for everyone (a badge on the
        // boards), regardless of acuity. Where the patient lands depends on acuity:
        //
        //   • RED / ORANGE  → bypass the triage DESK and are placed straight into a
        //     treatment zone (RED→Resus, ORANGE→Acute) via the SAME ZoneRoutingService
        //     the ED uses. A field-critical patient at the door is NOT made to wait in
        //     the triage line — they go to the resus/acute team immediately.
        //   • YELLOW / GREEN / BLUE → currentEdZone is deliberately left NULL so they
        //     enter the ED triage-desk queue for a formal triage, exactly like a
        //     walk-in (they are stable enough to be triaged in turn).
        //
        // In BOTH cases the visit STAYS AWAITING_TRIAGE and the ED re-triage clock
        // (EmsRetriageMonitor) still forces a formal triage, which can re-place/override
        // via TriageService.performTriage. Guarded so an already-triaged visit is never
        // re-placed from the (older) field category.
        TriageCategory fieldCat = parseFieldCategory(run.getFieldTriageCategory());
        if (fieldCat != null && v.getCurrentTriageCategory() == null) {
            v.setCurrentTriageCategory(fieldCat);
            if (fieldCat == TriageCategory.RED || fieldCat == TriageCategory.ORANGE) {
                v.setCurrentEdZone(zoneRoutingService.routeFor(v, fieldCat));
                log.info("[ems] Run {} high-acuity arrival {} → placed in zone {} (bypasses triage desk; "
                        + "visit {} stays AWAITING_TRIAGE for formal ED triage)",
                        runId, fieldCat, v.getCurrentEdZone(), v.getId());
            } else {
                log.info("[ems] Run {} lower-acuity arrival {} → enters the ED triage-desk queue "
                        + "(no zone placement; visit {} AWAITING_TRIAGE)", runId, fieldCat, v.getId());
            }
        }
        visitRepository.save(v);

        run = emsRunRepository.save(run);
        log.info("[ems] Run {} ARRIVED — visit {} re-triage due at {}",
                runId, v.getId(), v.getEdRetriageDueAt());

        // Issue-1 sync: the patient is now AT THE DOOR, so the "ambulance inbound"
        // (EMS_PRE_ARRIVAL) notification is superseded by the EMS_ARRIVED alert below.
        // Auto-acknowledge any open pre-arrival alert for this visit so it does not
        // linger in the Alert Center after arrival (best-effort; never blocks arrival).
        autoAcknowledgeEmsAlerts(v, List.of(AlertType.EMS_PRE_ARRIVAL),
                "Superseded — patient arrived at ED");

        // Notify the receiving team that the patient is physically AT THE DOOR.
        // Arrival was previously only a silent card-flip on the inbound board, so a
        // critical patient could arrive with no attention-grabbing cue until the
        // 10-min handover-pending escalation. This owned alert reaches the Alert
        // Center + the charge nurse immediately — CRITICAL (→ audible) for a
        // RED / lights arrival — routed to the provisional zone seeded above.
        publishArrivalAlert(run, redOrLights);

        return broadcastAndMap(run, true);
    }

    /**
     * Owned EMS_ARRIVED alert raised the instant a run is marked ARRIVED. Mirrors
     * the pre-arrival routing (charge nurse + zone board, fanned out AFTER COMMIT
     * so a rolled-back arrival never pushes a phantom alert). Severity is CRITICAL
     * for a RED / lights arrival so the client beeps; otherwise it tracks the field
     * category. Best-effort: a routing failure must never block the arrival itself.
     */
    private void publishArrivalAlert(EmsRun run, boolean critical) {
        Visit v = run.getVisit();
        EdZone zone = v != null ? v.getCurrentEdZone() : null;
        try {
            ClinicalAlert alert = ClinicalAlert.builder()
                    .visit(v)
                    .alertType(AlertType.EMS_ARRIVED)
                    .severity(critical ? AlertSeverity.CRITICAL : severityFor(run.getFieldTriageCategory()))
                    .title((critical ? "PATIENT AT DOOR — CRITICAL: " : "Ambulance arrived: ")
                            + safe(run.getMechanism(), "patient"))
                    .message(String.format(
                            "Patient is AT THE ED DOOR. Field triage %s%s. %s — acknowledge handover.",
                            run.getFieldTriageCategory() != null ? run.getFieldTriageCategory() : "?",
                            run.getFieldTewsScore() != null ? " · TEWS " + run.getFieldTewsScore() : "",
                            zone != null ? "Provisionally placed in " + zone : "Awaiting triage"))
                    .targetZone(zone)
                    .escalationTier(1)
                    .autoGenerated(true)
                    .build();
            alert = clinicalAlertRepository.save(alert);
            ClinicalAlertResponse resp = ClinicalAlertMapper.toResponse(alert);
            List<UUID> userIds = shiftAssignmentService.getChargeNurse(run.getHospital().getId()).stream()
                    .filter(cn -> cn != null && cn.getId() != null)
                    .map(User::getId)
                    .toList();
            realTimeEventPublisher.publishOwnedAlertAfterCommit(
                    run.getHospital().getId(), zone, resp, userIds);
        } catch (Exception e) {
            log.warn("[ems] Arrival alert routing failed for run {}: {}", run.getId(), e.getMessage());
        }
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

        // Immutable transfer-of-care attestation. The EmsRun columns above are
        // mutable display/state; THIS is the append-only legal record of the
        // transfer of responsibility, written as a HANDOVER ClinicalNote that is
        // principal-attributed (author resolved server-side from the authenticated
        // caller — NOT the client-supplied receivedByName, closing that spoof gap)
        // and never updated/deleted. Part of the same transaction as the status
        // change, so there is never a HANDED_OFF run without its attestation.
        if (run.getVisit() != null) {
            ClinicalNote attestation = ClinicalNote.builder()
                    .visit(run.getVisit())
                    .noteType(NoteType.HANDOVER)
                    .section("EMS Transfer of Care")
                    .content(buildHandoverAttestation(run, caller))
                    .recordedByName(caller != null
                            ? caller.getFirstName() + " " + caller.getLastName() : "Unknown")
                    .authorUserId(caller != null ? caller.getId() : null)
                    .authorRole(caller != null ? caller.getRole() : null)
                    .recordedAt(now)
                    .build();
            clinicalNoteRepository.save(attestation);
        }

        log.info("[ems] Run {} HANDED_OFF to {} (attestation recorded)", runId, run.getHandedOffToName());

        // Issue-1 sync: completing the handover from the dashboard clears the EMS
        // notifications in the Alert Center too — a clinician should never have to
        // acknowledge the same arrival twice. Auto-acknowledge any open pre-arrival /
        // arrived alerts for this visit (best-effort; never blocks the handover).
        if (run.getVisit() != null) {
            autoAcknowledgeEmsAlerts(run.getVisit(),
                    List.of(AlertType.EMS_PRE_ARRIVAL, AlertType.EMS_ARRIVED),
                    "Resolved — care handed over to " + safe(run.getHandedOffToName(), "ED"));
        }

        return broadcastAndMap(run, true);
    }

    /**
     * Acknowledge any OPEN (unacknowledged, active) EMS alerts of the given types
     * for a visit, attributing the ack to the authenticated caller, and re-publish
     * each AFTER COMMIT so live Alert Center / dashboard clients clear it. This is
     * the cross-surface sync for issue 1: a workflow step on the dashboard (arrival,
     * handover) closes the matching notification so it isn't acknowledged twice.
     * Best-effort — a routing/persistence hiccup here must never roll back or block
     * the workflow transition that triggered it.
     */
    private void autoAcknowledgeEmsAlerts(Visit visit, List<AlertType> types, String reason) {
        if (visit == null) return;
        try {
            User actor = currentUser().orElse(null);
            List<ClinicalAlert> open = clinicalAlertRepository
                    .findByVisitIdAndAlertTypeInAndIsAcknowledgedFalseAndIsActiveTrue(visit.getId(), types);
            if (open == null || open.isEmpty()) return;
            UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
            for (ClinicalAlert a : open) {
                a.setAcknowledged(true);
                a.setAcknowledgedAt(Instant.now());
                if (actor != null) a.setAcknowledgedBy(actor);
                if (reason != null && !reason.isBlank()) {
                    a.setAcknowledgmentNote(reason.length() > 1000 ? reason.substring(0, 1000) : reason);
                }
                ClinicalAlert saved = clinicalAlertRepository.save(a);
                // Fan out after commit so a rolled-back transition never pushes a phantom
                // "acknowledged" state. Per-alert try/catch so a publish hiccup on one alert
                // never prevents the OTHERS from being acknowledged (the state change above
                // is the critical part; the live push is a convenience).
                try {
                    ClinicalAlertResponse resp = ClinicalAlertMapper.toResponse(saved);
                    realTimeEventPublisher.publishOwnedAlertAfterCommit(
                            hospitalId, saved.getTargetZone(), resp, List.of());
                } catch (Exception pub) {
                    log.warn("[ems] Could not republish auto-acked alert {}: {}",
                            saved.getId(), pub.getMessage());
                }
            }
            log.info("[ems] Auto-acknowledged {} EMS alert(s) for visit {} — {}",
                    open.size(), visit.getId(), reason);
        } catch (Exception e) {
            log.warn("[ems] Auto-acknowledge of EMS alerts for visit {} failed: {}",
                    visit.getId(), e.getMessage());
        }
    }

    /**
     * Build the immutable transfer-of-care attestation text: who accepted the
     * patient (server-authenticated), from whom, the clinical state at the moment
     * of transfer, and the read-back. This is the legal record of what was true
     * when responsibility passed from the paramedic to the receiving clinician.
     */
    private String buildHandoverAttestation(EmsRun run, User receiver) {
        StringBuilder sb = new StringBuilder("EMS transfer of care accepted.\n");
        sb.append("Received by: ")
          .append(receiver != null ? receiver.getFirstName() + " " + receiver.getLastName() : "Unknown")
          .append(receiver != null && receiver.getRole() != null ? " (" + receiver.getRole() + ")" : "")
          .append('\n');
        sb.append("From paramedic: ").append(safe(run.getParamedicName(), "unknown")).append('\n');
        sb.append("Field triage at handover: ")
          .append(run.getFieldTriageCategory() != null ? run.getFieldTriageCategory() : "not assessed")
          .append(run.getFieldTewsScore() != null ? " · TEWS " + run.getFieldTewsScore() : "")
          .append(run.isLightsActive() ? " · lights / priority transport" : "")
          .append('\n');
        String ack = run.getHandoverAcknowledgementText();
        sb.append("Read-back: ").append(ack != null && !ack.isBlank() ? ack : "(none recorded)");
        return sb.toString();
    }

    /**
     * Redirect an in-flight run to a different destination hospital
     * (hospital change mid-transport). Permitted only before the patient is
     * physically at the door (DISPATCHED or EN_ROUTE).
     *
     * <ul>
     *   <li><b>No visit yet</b> (pre-arrival not sent): simply repoint the run
     *       at the new hospital.</li>
     *   <li><b>Pre-registered, unidentified placeholder</b>: stand down the
     *       old hospital (transfer + soft-delete the visit, deactivate its
     *       inbound alert) and re-announce at the new hospital with a fresh
     *       placeholder + pre-arrival alert.</li>
     *   <li><b>Pre-registered, identified patient</b>: blocked — the Patient
     *       row is hospital-scoped, so a cross-hospital move must be a
     *       deliberate cancel-and-re-register, never silent.</li>
     * </ul>
     */
    @Transactional
    public EmsRunResponse reroute(UUID runId, RerouteRequest req) {
        EmsRun run = findOrThrow(runId);
        if (run.getStatus() != EmsRunStatus.DISPATCHED && run.getStatus() != EmsRunStatus.EN_ROUTE) {
            throw new ClinicalBusinessException(
                    "Cannot reroute run " + runId + " from status " + run.getStatus()
                            + " — only before the patient is at the door.");
        }
        Hospital newHospital = hospitalService.findHospitalOrThrow(req.getHospitalId());
        Hospital oldHospital = run.getHospital();
        if (oldHospital != null && oldHospital.getId().equals(newHospital.getId())) {
            return broadcastAndMap(run, false); // no-op
        }

        Visit existing = run.getVisit();
        if (existing == null) {
            run.setHospital(newHospital);
            run = emsRunRepository.save(run);
            log.info("[ems] Run {} rerouted (pre-send) {} → {}", runId, code(oldHospital), code(newHospital));
            return broadcastAndMap(run, false);
        }

        Patient patient = existing.getPatient();
        if (patient != null && !patient.isUnidentified()) {
            throw new ClinicalBusinessException(
                    "Run " + runId + " is already pre-registered for an identified patient at "
                            + code(oldHospital) + ". Cancel that registration before rerouting to "
                            + code(newHospital) + ".");
        }

        // Stand down the old hospital, then re-announce at the new one.
        standDownVisit(existing, newHospital, req.getReason());
        run.setVisit(null);
        run.setHospital(newHospital);
        run.setStatus(EmsRunStatus.DISPATCHED); // preregister advances it back to EN_ROUTE
        run = emsRunRepository.save(run);

        PreregisterRequest pre = new PreregisterRequest();
        pre.setEtaMinutes(run.getEtaMinutes());
        pre.setPreArrivalNote("Rerouted from " + code(oldHospital)
                + (req.getReason() != null && !req.getReason().isBlank() ? " — " + req.getReason() : ""));
        EmsRunResponse resp = preregister(runId, pre);

        // Nudge the old hospital's boards so the ghost inbound clears.
        try {
            realTimeEventPublisher.publishEmsRun(oldHospital.getId(), resp);
        } catch (Exception e) {
            log.warn("[ems] reroute old-hospital broadcast failed: {}", e.getMessage());
        }
        log.info("[ems] Run {} rerouted {} → {} (visit re-created at destination)",
                runId, code(oldHospital), code(newHospital));
        return resp;
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

    /**
     * Render the run's Patient Care Report (PCR) PDF. Authorization is the SAME as {@link #getById}
     * ({@code findOrThrow} → {@code assertCallerMayAccess}: a paramedic may only render their own
     * run, ED staff/admin only runs at their hospital, super-admin any). Runs inside this read-only
     * transaction so the PDF service can resolve the lazy visit/patient/hospital associations.
     */
    public EmsPcrPdfService.RenderedPdf renderPcr(UUID runId) {
        EmsRun run = findOrThrow(runId);
        List<EmsIntervention> ivs = interventionRepository
                .findByEmsRunIdAndIsActiveTrueOrderByGivenAtAsc(runId);
        return new EmsPcrPdfService.RenderedPdf(
                emsPcrPdfService.render(run, ivs), emsPcrPdfService.filename(run));
    }

    @Transactional(readOnly = true)
    public List<EmsRunResponse> getInbound(UUID hospitalId) {
        List<EmsRun> runs = emsRunRepository.findInbound(hospitalId);
        // Scope the inbound board like the alerts: oversight (charge nurse / shift
        // lead / super-admin) coordinate the whole floor and see EVERY inbound
        // ambulance; a zone-bound nurse/doctor sees only ambulances headed to a zone
        // they currently cover, so a General nurse no longer sees a Resus-bound
        // critical's "at door" card. An undifferentiated inbound (no destination zone
        // yet) stays a charge-nurse concern until it's categorised.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!clinicalAuthz.canSeeAllZonesAtHospital(auth, hospitalId)) {
            Set<EdZone> covered = currentCoveredZones(hospitalId);
            runs = runs.stream()
                    .filter(r -> {
                        EdZone dest = inboundDestinationZone(r);
                        return dest != null && covered.contains(dest);
                    })
                    .collect(Collectors.toList());
        }
        return runs.stream().map(EmsRunMapper::toResponse).collect(Collectors.toList());
    }

    /**
     * The ED zone an inbound run is destined for, for board-scoping: the visit's
     * provisional placement once ARRIVED, otherwise the pre-arrival target zone
     * (RESUS for RED/lights, the field-category zone otherwise, null if undifferentiated).
     */
    private EdZone inboundDestinationZone(EmsRun run) {
        Visit v = run.getVisit();
        if (v != null && v.getCurrentEdZone() != null) {
            return v.getCurrentEdZone();
        }
        boolean critical = isRedField(run.getFieldTriageCategory()) || run.isLightsActive();
        return preArrivalTargetZone(run, critical);
    }

    /** The caller's currently-covered zones (active shift's primary ∪ additional). */
    private Set<EdZone> currentCoveredZones(UUID hospitalId) {
        User caller = currentUser().orElse(null);
        if (caller == null) {
            return Set.of();
        }
        Set<EdZone> zones = new HashSet<>();
        shiftAssignmentService.getCurrentShiftForUser(caller.getId()).ifPresent(sa -> {
            if (sa.getHospitalId() == null || sa.getHospitalId().equals(hospitalId)) {
                if (sa.getZone() != null) {
                    zones.add(sa.getZone());
                }
                if (sa.getAdditionalZones() != null) {
                    zones.addAll(sa.getAdditionalZones());
                }
            }
        });
        return zones;
    }

    public List<EmsRunResponse> getMyRuns() {
        User caller = currentUser().orElse(null);
        if (caller == null) return List.of();
        return emsRunRepository.findByParamedic(caller.getId())
                .stream().map(EmsRunMapper::toResponse).collect(Collectors.toList());
    }

    public Optional<EmsRunResponse> getByVisitId(UUID visitId) {
        return emsRunRepository.findByVisitIdAndIsActiveTrue(visitId)
                .map(r -> {
                    // Same tenant guard as the by-id path: a paramedic may read only
                    // their OWN run, others only runs destined for their hospital —
                    // closes the by-visit IDOR (a paramedic reading any same-hospital
                    // run via visitId, bypassing own-run-only).
                    assertCallerMayAccess(r);
                    return EmsRunMapper.toResponse(r,
                            interventionRepository.findByEmsRunIdAndIsActiveTrueOrderByGivenAtAsc(r.getId()));
                });
    }

    /**
     * Safety-critical context for the crew when the run is linked to a known
     * patient: allergies, chronic conditions, blood type, prior visits. For an
     * unidentified placeholder there is nothing to surface ({@code known=false}).
     */
    public PatientHistoryResponse getPatientHistory(UUID runId) {
        EmsRun run = findOrThrow(runId);
        Visit visit = run.getVisit();
        if (visit == null || visit.getPatient() == null) {
            return PatientHistoryResponse.builder().known(false).build();
        }
        Patient p = visit.getPatient();
        if (p.isUnidentified()) {
            String display = UnidentifiedPatientNameService.buildDisplayName(
                    p.getPlaceholderLabel(), visit.isPediatric());
            return PatientHistoryResponse.builder()
                    .known(false).unidentified(true).displayName(display).build();
        }
        var page = visitRepository.findByPatientIdAndIsActiveTrue(p.getId(), PageRequest.of(0, 50));
        long prior = Math.max(0, page.getTotalElements() - 1); // exclude the current visit
        String lastVisitAt = page.getContent().stream()
                .filter(v -> !v.getId().equals(visit.getId()))
                .map(Visit::getArrivalTime)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .map(Instant::toString)
                .orElse(null);
        return PatientHistoryResponse.builder()
                .known(true)
                .unidentified(false)
                .displayName((p.getFirstName() + " " + p.getLastName()).trim())
                .knownAllergies(p.getKnownAllergies())
                .chronicConditions(p.getChronicConditions())
                .bloodType(p.getBloodType())
                .priorVisitCount(prior)
                .lastVisitAt(lastVisitAt)
                .build();
    }

    /** Active hospitals the paramedic can pick as a destination. */
    public List<DestinationHospitalResponse> listDestinations() {
        return hospitalRepository.findByIsActiveTrue().stream()
                .map(h -> DestinationHospitalResponse.builder()
                        .id(h.getId())
                        .name(h.getName())
                        .hospitalCode(h.getHospitalCode())
                        .city(h.getCity())
                        .build())
                .sorted(java.util.Comparator.comparing(
                        d -> d.getName() == null ? "" : d.getName(), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    // ====================================================================
    // HELPERS
    // ====================================================================

    private EmsRun findOrThrow(UUID runId) {
        EmsRun run = emsRunRepository.findByIdAndIsActiveTrue(runId)
                .orElseThrow(() -> new ResourceNotFoundException("EmsRun", "id", runId));
        assertCallerMayAccess(run);
        return run;
    }

    /**
     * Tenant scope for by-id run access (read + every mutation routes through
     * findOrThrow). Closes the IDOR where any authenticated user could reach
     * another hospital's run by guessing its id. Rules:
     * <ul>
     *   <li>SUPER_ADMIN — any run.</li>
     *   <li>PARAMEDIC — only runs they own (run.paramedic == caller),
     *       regardless of destination hospital (a crew may transport across
     *       facilities, so we scope by ownership, not hospital).</li>
     *   <li>everyone else (NURSE / DOCTOR / HOSPITAL_ADMIN / READ_ONLY) —
     *       only runs destined for their own hospital.</li>
     * </ul>
     */
    private void assertCallerMayAccess(EmsRun run) {
        User caller = currentUser().orElse(null);
        if (caller == null) {
            throw new AccessDeniedException("Not authenticated");
        }
        if (caller.getRole() == Role.SUPER_ADMIN) {
            return;
        }
        if (caller.getRole() == Role.PARAMEDIC) {
            if (run.getParamedic() != null && caller.getId().equals(run.getParamedic().getId())) {
                return;
            }
            throw new AccessDeniedException("Paramedics may only access their own EMS runs");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID runHospitalId = run.getHospital() != null ? run.getHospital().getId() : null;
        if (runHospitalId == null || !clinicalAuthz.canAccessHospital(auth, runHospitalId)) {
            throw new AccessDeniedException("EMS run belongs to a different hospital");
        }
    }

    private void ensureMutable(EmsRun run, String action) {
        if (run.getStatus() == EmsRunStatus.HANDED_OFF
                || run.getStatus() == EmsRunStatus.CANCELLED) {
            throw new ClinicalBusinessException(
                    "Cannot " + action + " on run " + run.getId() + " — status is " + run.getStatus());
        }
    }

    /**
     * Push the pre-arrival alert onto the live alert channels — not merely
     * persist it. Hospital-wide so the alert centre + CriticalAlertNotifier
     * (audible/flash on CRITICAL) fire; RESUS-zone for critical runs so the
     * resus team can prep a bay before the doors open; and user-targeted to
     * the on-duty charge nurse(s) — the clinically appropriate first
     * recipient for an inbound, identified the same way the alert-escalation
     * service does.
     */
    /**
     * Re-escalate the inbound pre-arrival to CRITICAL / RESUS when a run that
     * was ALREADY pre-registered (EN_ROUTE, visit linked) becomes critical
     * after the first ping — blue lights switched on, or a field re-triage
     * upgraded the category to RED. {@link #preregister} fixes criticality
     * exactly once; without this a deterioration after the ping never reaches
     * the RESUS board and the ED keeps prepping for a routine arrival.
     *
     * No-op unless the run is inbound, now critical, and no CRITICAL
     * pre-arrival is already live for the visit (so toggling lights on/off or
     * recomputing repeatedly never spams the resus team).
     */
    private void escalatePreArrivalIfNowCritical(EmsRun run, String trigger) {
        if (run.getVisit() == null) return;                       // not pre-registered yet — preregister handles it
        if (run.getStatus() != EmsRunStatus.EN_ROUTE) return;     // only while genuinely inbound
        boolean critical = isRedField(run.getFieldTriageCategory()) || run.isLightsActive();
        if (!critical) return;
        UUID visitId = run.getVisit().getId();
        boolean alreadyCritical = clinicalAlertRepository
                .existsByVisitIdAndAlertTypeAndSeverityAndIsActiveTrue(
                        visitId, AlertType.EMS_PRE_ARRIVAL, AlertSeverity.CRITICAL);
        if (alreadyCritical) return;                              // ED already has the CRITICAL inbound
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(run.getVisit())
                .alertType(AlertType.EMS_PRE_ARRIVAL)
                .severity(AlertSeverity.CRITICAL)
                .title("INCOMING CRITICAL — " + safe(run.getMechanism(), "patient"))
                .message(String.format(
                        "Pre-arrival UPGRADED to CRITICAL (%s). Field triage %s%s. %s",
                        trigger,
                        run.getFieldTriageCategory() != null ? run.getFieldTriageCategory() : "?",
                        run.isLightsActive() ? ", lights / priority transport active" : "",
                        safe(run.getMechanism(), "EMS patient en route")))
                .targetZone(EdZone.RESUS)
                .escalationTier(2)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);
        routePreArrivalAlert(run, alert, true);
        log.warn("[ems] Pre-arrival re-escalated to CRITICAL for visit {} (run {}) — {}",
                visitId, run.getId(), trigger);
    }

    private void routePreArrivalAlert(EmsRun run, ClinicalAlert alert, boolean critical) {
        UUID hospitalId = run.getHospital().getId();
        EdZone targetZone = alert.getTargetZone();   // RESUS (RED/lights) or the field-category placement zone
        try {
            ClinicalAlertResponse resp = ClinicalAlertMapper.toResponse(alert);
            // Resolve the charge nurse(s) in-transaction, then fan out AFTER COMMIT so a
            // rolled-back pre-registration never pushes a phantom inbound alert.
            List<User> chargeNurses = shiftAssignmentService.getChargeNurse(hospitalId);
            if (chargeNurses.isEmpty()) {
                // The intended primary recipient is missing — make it visible. The inbound
                // still reaches its destination-zone board (RESUS for RED/lights, ACUTE for
                // ORANGE, …); an untriaged inbound has no zone target so falls back to the
                // hospital-wide board only.
                log.warn("[ems] No on-shift charge nurse at hospital {} to receive the inbound pre-arrival "
                        + "for run {} — relying on hospital-wide{} broadcast.",
                        hospitalId, run.getId(),
                        targetZone != null ? " + " + targetZone + "-zone" : " (no zone target)");
            }
            List<UUID> userIds = chargeNurses.stream()
                    .filter(cn -> cn != null && cn.getId() != null).map(User::getId).toList();
            // Route to the destination zone the patient will land in, so the receiving zone
            // team preps before arrival (RED/lights→RESUS; ORANGE→ACUTE; YELLOW→Observation;
            // GREEN→Ambulatory). The alert carries the zone (preArrivalTargetZone).
            realTimeEventPublisher.publishOwnedAlertAfterCommit(
                    hospitalId, targetZone, resp, userIds);
        } catch (Exception e) {
            log.warn("[ems] Pre-arrival alert routing failed for run {}: {}", run.getId(), e.getMessage());
        }
    }

    /**
     * Destination zone a pre-arrival alert should target so the receiving team
     * preps the right bay before the patient lands: RESUS for a RED / lights
     * (priority-transport) inbound, otherwise the field triage category's
     * placement zone via the same {@link ZoneRoutingService} the ED uses
     * (ORANGE→ACUTE, YELLOW→OBSERVATION, GREEN→AMBULATORY, …). Returns null for an
     * untriaged inbound (no field category yet) → hospital-wide board only.
     */
    private EdZone preArrivalTargetZone(EmsRun run, boolean critical) {
        if (critical) {
            return EdZone.RESUS;
        }
        TriageCategory fieldCat = parseFieldCategory(run.getFieldTriageCategory());
        return fieldCat != null ? zoneRoutingService.routeFor(run.getVisit(), fieldCat) : null;
    }

    /** Mark a rerouted-away visit as transferred + inactive and clear its inbound alerts. */
    private void standDownVisit(Visit visit, Hospital reroutedTo, String reason) {
        visit.setStatus(VisitStatus.TRANSFERRED);
        visit.setDispositionNotes("Ambulance rerouted to " + code(reroutedTo)
                + (reason != null && !reason.isBlank() ? " — " + reason : ""));
        visit.softDelete();
        visitRepository.save(visit);

        // A placeholder patient minted only for this run is now unused — retire it.
        Patient p = visit.getPatient();
        if (p != null && p.isUnidentified()) {
            p.softDelete();
            patientRepository.save(p);
        }

        var alerts = clinicalAlertRepository
                .findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(visit.getId(), PageRequest.of(0, 20));
        for (ClinicalAlert a : alerts.getContent()) {
            if (a.getAlertType() == AlertType.EMS_PRE_ARRIVAL
                    || a.getAlertType() == AlertType.FIELD_TRIAGED_AWAITING_REVIEW) {
                a.softDelete();
                clinicalAlertRepository.save(a);
            }
        }
    }

    private static boolean isRedField(String fieldTriageCategory) {
        return "RED".equalsIgnoreCase(fieldTriageCategory);
    }

    /**
     * Parse the persisted field-triage category String (RED/ORANGE/YELLOW/GREEN/
     * BLUE) into the hospital {@link TriageCategory} enum used for zone placement.
     * Returns null (no provisional placement) for a blank or unrecognised value
     * rather than throwing — a placement step must never block an arrival.
     */
    private static TriageCategory parseFieldCategory(String fieldTriageCategory) {
        if (fieldTriageCategory == null || fieldTriageCategory.isBlank()) {
            return null;
        }
        try {
            return TriageCategory.valueOf(fieldTriageCategory.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[ems] Unrecognised field triage category '{}' — skipping provisional placement",
                    fieldTriageCategory);
            return null;
        }
    }

    private static String code(Hospital h) {
        return h != null ? h.getHospitalCode() : "?";
    }

    private EmsRunResponse broadcastAndMap(EmsRun run, boolean withInterventions) {
        List<EmsIntervention> ivs = withInterventions
                ? interventionRepository.findByEmsRunIdAndIsActiveTrueOrderByGivenAtAsc(run.getId())
                : null;
        EmsRunResponse response = EmsRunMapper.toResponse(run, ivs);
        try {
            // After-commit so a rolled-back mutation never pushes a phantom run
            // to the inbound board (matches the visit/alert publish discipline).
            realTimeEventPublisher.publishEmsRunAfterCommit(run.getHospital().getId(), response);
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
        // Carry the paramedic's MIST/SBAR handover notes into the alert text so
        // the charge nurse reads context from the live ping, not only the modal.
        if (run.getNotes() != null && !run.getNotes().isBlank()) {
            s.append(" — handover: ").append(run.getNotes());
        }
        if (req != null && req.getPreArrivalNote() != null && !req.getPreArrivalNote().isBlank()) {
            s.append(" — ").append(req.getPreArrivalNote());
        }
        return s.toString();
    }

    private static String safe(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
