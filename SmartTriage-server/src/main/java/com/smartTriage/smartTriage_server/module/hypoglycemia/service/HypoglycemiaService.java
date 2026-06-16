package com.smartTriage.smartTriage_server.module.hypoglycemia.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.GlucoseUnit;
import com.smartTriage.smartTriage_server.common.enums.HypoglycemiaSeverity;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.HypoglycemiaCheckResponse;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.RecordTreatmentRequest;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.RepeatGlucoseRequest;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.HypoglycemiaEnforcementEngine;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.HypoglycemiaEnforcementEngine.HypoglycemiaCheckResult;
import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HypoglycemiaService — detection, owned real-time alerting, treatment + recheck.
 *
 * Hypoglycemia is fatal in minutes, so a detected low glucose raises a dedicated
 * HYPOGLYCEMIA_CRITICAL alert that is OWNED (zone doctor + charge nurse) and
 * pushed in real time, schedules a mandatory 15-minute recheck (enforced by
 * {@code HypoglycemiaRecheckMonitorService}), and records a time-stamped trail
 * with the authenticated actor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HypoglycemiaService {

    /** Mandatory recheck interval after detection / treatment. */
    static final Duration RECHECK_INTERVAL = Duration.ofMinutes(15);

    /**
     * Physiologic plausibility window (mmol/L) for a repeat glucose AFTER unit
     * conversion. Outside this band the value is a unit/data error and is rejected.
     */
    static final double PLAUSIBLE_MIN_MMOL = 0.3;
    static final double PLAUSIBLE_MAX_MMOL = 60.0;

    /**
     * A repeat glucose is only allowed to <i>auto-resolve</i> an event when it is
     * a believable recovered reading (≤ this, mmol/L). A normal-classifying but
     * implausibly-high repeat (e.g. a mg/dL value typed without switching units,
     * or an over-treatment artefact) does NOT silently close the event — the
     * clinician must resolve it explicitly. This is the safe direction: we never
     * mark a still-hypoglycemic patient "recovered" because of a unit slip.
     */
    static final double AUTO_RESOLVE_MAX_MMOL = 15.0;

    private final HypoglycemiaEventRepository hypoglycemiaEventRepository;
    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final HypoglycemiaEnforcementEngine enforcementEngine;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final ShiftAssignmentService shiftAssignmentService;

    /**
     * Run hypoglycemia enforcement for a visit from the latest TRIAGE record.
     */
    @Transactional
    public HypoglycemiaCheckResponse checkAndEnforce(UUID visitId) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));
        TriageRecord triage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No triage record found for visit: " + visitId));

        HypoglycemiaCheckResult result = enforcementEngine.enforceGlucoseCheck(visit, triage);

        HypoglycemiaCheckResponse.HypoglycemiaCheckResponseBuilder responseBuilder = HypoglycemiaCheckResponse.builder()
                .visitId(visitId)
                .requiresCheck(result.requiresCheck())
                .checkMandatory(result.checkMandatory())
                .glucoseValue(result.glucoseValue())
                .isHypoglycemic(result.isHypoglycemic())
                .severity(result.severity().name())
                .treatmentProtocol(result.treatmentProtocol())
                .triggerReasons(result.triggerReasons());

        if (!result.requiresCheck()) {
            return responseBuilder.build();
        }

        if (result.isHypoglycemic()) {
            HypoglycemiaEvent event = createEventAndAlert(visit, result, "TRIAGE");
            if (event != null) responseBuilder.eventId(event.getId());
        } else if (result.glucoseValue() == null && result.requiresCheck()) {
            // A check is required (mandatory OR recommended-for-known-diabetic) but no
            // glucose is on file — surface the glucose-check-required alert in BOTH cases
            // (previously only the mandatory branch alerted, so a diabetic with no glucose
            // was silent).
            generateGlucoseCheckRequiredAlert(visit, result);
            log.warn("Glucose check required but not performed for visit {} ({})",
                    visitId, String.join(", ", result.triggerReasons()));
        }
        return responseBuilder.build();
    }

    /**
     * Auto-detection entry point for a glucose reading arriving from ANY live
     * source (manual/POC vitals, IoT stream). Best-effort: never throws, so it
     * can never break the vitals write that triggered it (mirrors the S3
     * deterioration hook). Creates an owned event + alert when hypoglycemic.
     */
    @Transactional
    public void evaluateGlucoseReading(Visit visit, Double glucose, boolean neuroglycopenia, String source) {
        try {
            if (visit == null || glucose == null) return;
            HypoglycemiaCheckResult result = enforcementEngine.interpret(
                    visit, glucose, neuroglycopenia, true, true,
                    List.of(source == null ? "glucose_reading" : source.toLowerCase()));
            if (result.isHypoglycemic()) {
                createEventAndAlert(visit, result, source != null ? source : "VITALS");
            }
        } catch (Exception e) {
            log.warn("Hypoglycemia auto-evaluation failed for visit {}: {}",
                    visit != null ? visit.getId() : null, e.getMessage());
        }
    }

    @Transactional
    public HypoglycemiaEvent recordTreatment(UUID eventId, RecordTreatmentRequest request) {
        HypoglycemiaEvent event = hypoglycemiaEventRepository.findByIdAndIsActiveTrue(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("HypoglycemiaEvent", "id", eventId));
        Instant now = Instant.now();
        event.setTreatmentGiven(request.getTreatment());
        event.setTreatmentGivenAt(now);
        // Actor is the authenticated user; fall back to the (optional) request name.
        String actor = resolveCurrentUserName();
        event.setTreatmentGivenByName(actor != null ? actor : request.getTreatedByName());
        // Treatment given → mandatory recheck in 15 minutes.
        event.setRecheckDueAt(now.plus(RECHECK_INTERVAL));
        event = hypoglycemiaEventRepository.save(event);
        publishDashboardEvent(event, "TREATED");
        log.info("Treatment recorded for hypoglycemia event {} by {}", eventId, event.getTreatmentGivenByName());
        return event;
    }

    @Transactional
    public HypoglycemiaEvent recordRepeatGlucose(UUID eventId, RepeatGlucoseRequest request) {
        HypoglycemiaEvent event = hypoglycemiaEventRepository.findByIdAndIsActiveTrue(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("HypoglycemiaEvent", "id", eventId));
        Instant now = Instant.now();

        // Convert the entered value to mmol/L using its declared unit, then reject
        // anything outside the physiologic window — range alone cannot disambiguate
        // mmol/L from mg/dL, so we rely on the unit AND a plausibility floor/ceiling.
        GlucoseUnit unit = request.getUnit() != null ? request.getUnit() : GlucoseUnit.MMOL_L;
        double glucoseMmol = unit.toMmolL(request.getGlucoseLevel());
        if (glucoseMmol < PLAUSIBLE_MIN_MMOL || glucoseMmol > PLAUSIBLE_MAX_MMOL) {
            throw new IllegalArgumentException(String.format(
                    "Repeat glucose %.1f %s converts to %.1f mmol/L, outside the physiologic range "
                    + "(%.1f–%.1f mmol/L). Check the value and unit.",
                    request.getGlucoseLevel(), unit, glucoseMmol, PLAUSIBLE_MIN_MMOL, PLAUSIBLE_MAX_MMOL));
        }

        event.setRepeatGlucoseLevel(glucoseMmol);
        event.setRepeatGlucoseAt(now);

        HypoglycemiaSeverity repeatSeverity =
                enforcementEngine.classify(glucoseMmol, event.isNeonatal(), false);

        if (repeatSeverity.isHypoglycemic()) {
            // Still hypoglycemic after treatment → raise a NEW owned escalation and
            // re-arm the recheck clock (previously this only logged a warning).
            event.setSeverity(repeatSeverity.name());
            event.setRecheckDueAt(now.plus(RECHECK_INTERVAL));
            log.warn("Hypoglycemia event {} STILL {} after recheck: {} mmol/L — escalating",
                    eventId, repeatSeverity, glucoseMmol);
            raisePersistentHypoglycemiaAlert(event, repeatSeverity, glucoseMmol);
        } else if (glucoseMmol <= AUTO_RESOLVE_MAX_MMOL) {
            // Believable recovered reading → resolve, stop the recheck clock.
            event.setResolved(true);
            event.setResolvedAt(now);
            event.setResolvedByName(resolveCurrentUserName());
            event.setRecheckDueAt(null);
            log.info("Hypoglycemia event {} resolved — repeat glucose {} mmol/L", eventId, glucoseMmol);
        } else {
            // Classifies as not-hypoglycemic but implausibly HIGH for a recovery
            // (a likely unit slip, e.g. a mg/dL value entered as mmol/L). Do NOT
            // auto-resolve a still-open critical event on a suspect value — keep it
            // open, re-arm the recheck, and require an explicit clinician resolve.
            event.setRecheckDueAt(now.plus(RECHECK_INTERVAL));
            log.warn("Hypoglycemia event {} NOT auto-resolved — repeat glucose {} mmol/L is implausibly high "
                    + "for a recovery (possible unit/data error); event kept open for explicit resolve.",
                    eventId, glucoseMmol);
        }
        event = hypoglycemiaEventRepository.save(event);
        publishDashboardEvent(event, "RECHECKED");
        return event;
    }

    @Transactional
    public HypoglycemiaEvent resolveEvent(UUID eventId) {
        HypoglycemiaEvent event = hypoglycemiaEventRepository.findByIdAndIsActiveTrue(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("HypoglycemiaEvent", "id", eventId));
        event.setResolved(true);
        event.setResolvedAt(Instant.now());
        event.setResolvedByName(resolveCurrentUserName());
        event.setRecheckDueAt(null);
        event = hypoglycemiaEventRepository.save(event);
        publishDashboardEvent(event, "RESOLVED");
        log.info("Hypoglycemia event resolved: {} by {}", eventId, event.getResolvedByName());
        return event;
    }

    public List<HypoglycemiaEvent> getActiveEvents(UUID hospitalId) {
        return hypoglycemiaEventRepository.findActiveEventsByHospital(hospitalId);
    }

    public List<HypoglycemiaEvent> getEventsForVisit(UUID visitId) {
        return hypoglycemiaEventRepository.findByVisitIdAndIsActiveTrueOrderByDetectedAtDesc(visitId);
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    /** Create the event + owned alert when no unresolved event already exists. */
    private HypoglycemiaEvent createEventAndAlert(Visit visit, HypoglycemiaCheckResult result, String source) {
        if (hypoglycemiaEventRepository.existsByVisitIdAndResolvedFalseAndIsActiveTrue(visit.getId())) {
            return null;
        }
        Instant now = Instant.now();
        HypoglycemiaEvent event = HypoglycemiaEvent.builder()
                .visit(visit)
                .detectedAt(now)
                .glucoseLevel(result.glucoseValue())
                .triggerReason(String.join(", ", result.triggerReasons()))
                .severity(result.severity().name())
                .glucoseSource(source)
                .neonatal(result.neonatal())
                .detectedByName(resolveCurrentUserName())
                .recheckDueAt(now.plus(RECHECK_INTERVAL))
                .build();
        event = hypoglycemiaEventRepository.save(event);
        generateHypoglycemiaAlert(visit, result);
        publishDashboardEvent(event, "DETECTED");
        log.warn("Hypoglycemia event created: id={}, severity={}, glucose={} mmol/L, source={}, neonate={}",
                event.getId(), result.severity(), result.glucoseValue(), source, result.neonatal());
        return event;
    }

    private void generateHypoglycemiaAlert(Visit visit, HypoglycemiaCheckResult result) {
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = resolveZoneDoctor(hospitalId, zone);
        AlertSeverity alertSeverity = result.severity().isCritical() ? AlertSeverity.CRITICAL : AlertSeverity.HIGH;

        String title = String.format("HYPOGLYCEMIA %s%s: Glucose %.1f mmol/L",
                result.severity(), result.neonatal() ? " (NEONATAL)" : "", result.glucoseValue());
        String message = String.format(
                "Hypoglycemia detected for %s (Visit: %s). Glucose %.1f mmol/L (%s). %s "
                + "Recheck glucose in 15 minutes. Trigger: %s.",
                patientName(visit), visit.getVisitNumber(), result.glucoseValue(), result.severity(),
                result.treatmentProtocol() != null ? result.treatmentProtocol() : "",
                String.join(", ", result.triggerReasons()));

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.HYPOGLYCEMIA_CRITICAL)
                .severity(alertSeverity)
                .title(title)
                .message(message)
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .escalationTier(1)
                .build();
        alert = clinicalAlertRepository.save(alert);
        publishHypoglycemiaAlert(alert, hospitalId, zone, zoneDoctor);
        log.warn("{} HYPOGLYCEMIA alert generated: visit={}, zone={}, doctor={}",
                alertSeverity, visit.getId(), zone, zoneDoctor != null ? zoneDoctor.getId() : "unassigned");
    }

    private void raisePersistentHypoglycemiaAlert(HypoglycemiaEvent event, HypoglycemiaSeverity severity, double glucose) {
        Visit visit = event.getVisit();
        if (visit == null) return;
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = resolveZoneDoctor(hospitalId, zone);
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.HYPOGLYCEMIA_CRITICAL)
                .severity(AlertSeverity.CRITICAL)
                .title(String.format("HYPOGLYCEMIA PERSISTS (%s): Glucose %.1f mmol/L on recheck", severity, glucose))
                .message(String.format(
                        "Repeat glucose for %s (Visit: %s) is STILL %.1f mmol/L (%s) after treatment. "
                        + "Repeat dextrose and re-recheck in 15 minutes; escalate.",
                        patientName(visit), visit.getVisitNumber(), glucose, severity))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .escalationTier(2)
                .build();
        alert = clinicalAlertRepository.save(alert);
        publishHypoglycemiaAlert(alert, hospitalId, zone, zoneDoctor);
    }

    private void generateGlucoseCheckRequiredAlert(Visit visit, HypoglycemiaCheckResult result) {
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = resolveZoneDoctor(hospitalId, zone);
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.HYPOGLYCEMIA_CRITICAL)
                .severity(result.checkMandatory() ? AlertSeverity.HIGH : AlertSeverity.MEDIUM)
                .title("GLUCOSE CHECK REQUIRED")
                .message(String.format(
                        "Glucose measurement is %s for %s (Visit: %s). Trigger: %s. Check a bedside glucose now.",
                        result.checkMandatory() ? "MANDATORY" : "recommended",
                        patientName(visit), visit.getVisitNumber(), String.join(", ", result.triggerReasons())))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .escalationTier(1)
                .build();
        alert = clinicalAlertRepository.save(alert);
        publishHypoglycemiaAlert(alert, hospitalId, zone, zoneDoctor);
    }

    /**
     * Push the alert to the zone board + zone doctor + charge nurse(s) in real time.
     * Best-effort, and DEFERRED until after the surrounding transaction commits:
     * this method runs inside the vitals / IoT-ingest write transaction, which can
     * roll back (e.g. an optimistic-lock clash on the device session). Broadcasting
     * the alert only after commit guarantees clinicians never receive a CRITICAL
     * hypoglycemia alert whose backing event + alert rows were never persisted (no
     * 404 on click, and the recheck monitor — which scans persisted rows — stays
     * consistent). The response is built now (in-tx, session open) and captured.
     */
    private void publishHypoglycemiaAlert(ClinicalAlert alert, UUID hospitalId, EdZone zone, User zoneDoctor) {
        if (hospitalId == null || alert == null) return;
        final var resp = ClinicalAlertMapper.toResponse(alert);
        final UUID doctorId = zoneDoctor != null ? zoneDoctor.getId() : null;
        final List<UUID> chargeNurseIds = shiftAssignmentService.getChargeNurse(hospitalId)
                .stream().map(User::getId).toList();
        final UUID alertId = alert.getId();
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                if (zone != null) realTimeEventPublisher.publishZoneAlert(hospitalId, zone, resp);
                if (doctorId != null) realTimeEventPublisher.publishUserAlert(doctorId, resp);
                for (UUID cnId : chargeNurseIds) {
                    realTimeEventPublisher.publishUserAlert(cnId, resp);
                }
            } catch (Exception e) {
                log.warn("Failed to publish hypoglycemia alert {}: {}", alertId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { fire.run(); }
            });
        } else {
            fire.run();
        }
    }

    private void publishDashboardEvent(HypoglycemiaEvent event, String eventType) {
        try {
            Visit visit = event.getVisit();
            UUID hospitalId = visit != null && visit.getHospital() != null ? visit.getHospital().getId() : null;
            if (hospitalId != null) {
                realTimeEventPublisher.publishHypoglycemiaEventAfterCommit(hospitalId, Map.of(
                        "eventType", eventType,
                        "visitId", visit.getId().toString()));
            }
        } catch (Exception e) {
            log.warn("Failed to publish hypoglycemia dashboard event: {}", e.getMessage());
        }
    }

    private User resolveZoneDoctor(UUID hospitalId, EdZone zone) {
        if (hospitalId == null || zone == null) return null;
        List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
        return doctors.isEmpty() ? null : doctors.get(0);
    }

    private String patientName(Visit visit) {
        if (visit.getPatient() == null) return "patient";
        return visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();
    }

    private String resolveCurrentUserName() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) {
                return user.getFirstName() + " " + user.getLastName();
            }
        } catch (Exception ignored) {
            // no resolvable principal (scheduled / IoT context)
        }
        return null;
    }
}
