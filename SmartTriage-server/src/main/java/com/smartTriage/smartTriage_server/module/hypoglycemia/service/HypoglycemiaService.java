package com.smartTriage.smartTriage_server.module.hypoglycemia.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.HypoglycemiaCheckResponse;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.RecordTreatmentRequest;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.RepeatGlucoseRequest;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.HypoglycemiaEnforcementEngine;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.HypoglycemiaEnforcementEngine.HypoglycemiaCheckResult;
import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HypoglycemiaService — manages hypoglycemia enforcement, event tracking, and treatment recording.
 *
 * When hypoglycemia is detected, creates a ClinicalAlert with VITAL_SIGN_ABNORMAL type.
 * When a mandatory glucose check has not been performed, creates an alert requiring glucose check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HypoglycemiaService {

    private final HypoglycemiaEventRepository hypoglycemiaEventRepository;
    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final HypoglycemiaEnforcementEngine enforcementEngine;

    /**
     * Run hypoglycemia enforcement check for a visit.
     * Creates events and alerts as needed.
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
                .severity(result.severity())
                .treatmentProtocol(result.treatmentProtocol())
                .triggerReasons(result.triggerReasons());

        if (!result.requiresCheck()) {
            return responseBuilder.build();
        }

        // If glucose is available and hypoglycemic, create an event
        if (result.isHypoglycemic()) {
            // Check for existing unresolved event
            if (!hypoglycemiaEventRepository.existsByVisitIdAndResolvedFalseAndIsActiveTrue(visitId)) {
                HypoglycemiaEvent event = HypoglycemiaEvent.builder()
                        .visit(visit)
                        .detectedAt(Instant.now())
                        .glucoseLevel(result.glucoseValue())
                        .triggerReason(String.join(", ", result.triggerReasons()))
                        .severity(result.severity())
                        .build();
                event = hypoglycemiaEventRepository.save(event);
                responseBuilder.eventId(event.getId());

                // Generate clinical alert
                generateHypoglycemiaAlert(visit, result);

                log.info("Hypoglycemia event created: id={}, severity={}, glucose={} mmol/L",
                        event.getId(), result.severity(), result.glucoseValue());
            }
        } else if (result.glucoseValue() == null && result.checkMandatory()) {
            // Glucose check is mandatory but not yet performed — generate alert
            generateGlucoseCheckRequiredAlert(visit, result);
            log.warn("Mandatory glucose check not performed for visit {}", visitId);
        }

        return responseBuilder.build();
    }

    /**
     * Record treatment given for a hypoglycemia event.
     */
    @Transactional
    public HypoglycemiaEvent recordTreatment(UUID eventId, RecordTreatmentRequest request) {
        HypoglycemiaEvent event = hypoglycemiaEventRepository.findByIdAndIsActiveTrue(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("HypoglycemiaEvent", "id", eventId));

        event.setTreatmentGiven(request.getTreatment());
        event.setTreatmentGivenAt(Instant.now());
        event.setTreatmentGivenByName(request.getTreatedByName());

        event = hypoglycemiaEventRepository.save(event);

        log.info("Treatment recorded for hypoglycemia event {}: {}", eventId, request.getTreatment());
        return event;
    }

    /**
     * Record a repeat (follow-up) glucose check for a hypoglycemia event.
     */
    @Transactional
    public HypoglycemiaEvent recordRepeatGlucose(UUID eventId, RepeatGlucoseRequest request) {
        HypoglycemiaEvent event = hypoglycemiaEventRepository.findByIdAndIsActiveTrue(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("HypoglycemiaEvent", "id", eventId));

        event.setRepeatGlucoseLevel(request.getGlucoseLevel());
        event.setRepeatGlucoseAt(Instant.now());

        // If repeat glucose is normal, auto-resolve
        if (request.getGlucoseLevel() >= 4.0) {
            event.setResolved(true);
            event.setResolvedAt(Instant.now());
            log.info("Hypoglycemia event {} resolved — repeat glucose {} mmol/L (normal)",
                    eventId, request.getGlucoseLevel());
        } else if (request.getGlucoseLevel() < 3.0) {
            log.warn("Repeat glucose still CRITICAL for event {}: {} mmol/L — requires further treatment",
                    eventId, request.getGlucoseLevel());
        }

        event = hypoglycemiaEventRepository.save(event);
        return event;
    }

    /**
     * Mark a hypoglycemia event as resolved.
     */
    @Transactional
    public HypoglycemiaEvent resolveEvent(UUID eventId) {
        HypoglycemiaEvent event = hypoglycemiaEventRepository.findByIdAndIsActiveTrue(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("HypoglycemiaEvent", "id", eventId));

        event.setResolved(true);
        event.setResolvedAt(Instant.now());

        event = hypoglycemiaEventRepository.save(event);

        log.info("Hypoglycemia event resolved: {}", eventId);
        return event;
    }

    /**
     * Get all active (unresolved) hypoglycemia events for a hospital.
     */
    public List<HypoglycemiaEvent> getActiveEvents(UUID hospitalId) {
        return hypoglycemiaEventRepository.findActiveEventsByHospital(hospitalId);
    }

    /**
     * Get all hypoglycemia events for a specific visit.
     */
    public List<HypoglycemiaEvent> getEventsForVisit(UUID visitId) {
        return hypoglycemiaEventRepository.findByVisitIdAndIsActiveTrueOrderByDetectedAtDesc(visitId);
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private void generateHypoglycemiaAlert(Visit visit, HypoglycemiaCheckResult result) {
        AlertSeverity severity = "CRITICAL".equals(result.severity())
                ? AlertSeverity.CRITICAL : AlertSeverity.HIGH;

        String title = String.format("HYPOGLYCEMIA %s: Glucose %.1f mmol/L",
                result.severity(), result.glucoseValue());
        String message = String.format(
                "Hypoglycemia detected for visit %s. Glucose: %.1f mmol/L. Severity: %s. " +
                        "Treatment protocol: %s. Trigger: %s.",
                visit.getVisitNumber(),
                result.glucoseValue(),
                result.severity(),
                result.treatmentProtocol() != null ? result.treatmentProtocol() : "N/A",
                String.join(", ", result.triggerReasons()));

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.VITAL_SIGN_ABNORMAL)
                .severity(severity)
                .title(title)
                .message(message)
                .autoGenerated(true)
                .escalationTier(1)
                .build();

        clinicalAlertRepository.save(alert);
        log.info("{} alert generated for hypoglycemia: visit={}", severity, visit.getId());
    }

    private void generateGlucoseCheckRequiredAlert(Visit visit, HypoglycemiaCheckResult result) {
        String title = "GLUCOSE CHECK REQUIRED";
        String message = String.format(
                "Mandatory glucose check has not been performed for visit %s. " +
                        "Trigger reasons: %s. Per Rwanda protocol, glucose measurement is mandatory " +
                        "for patients with altered consciousness, convulsions, or coma.",
                visit.getVisitNumber(),
                String.join(", ", result.triggerReasons()));

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.VITAL_SIGN_ABNORMAL)
                .severity(AlertSeverity.HIGH)
                .title(title)
                .message(message)
                .autoGenerated(true)
                .escalationTier(1)
                .build();

        clinicalAlertRepository.save(alert);
        log.info("Glucose check required alert generated: visit={}", visit.getId());
    }
}
