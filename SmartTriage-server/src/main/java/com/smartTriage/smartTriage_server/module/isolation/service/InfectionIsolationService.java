package com.smartTriage.smartTriage_server.module.isolation.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.InfectionRiskLevel;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningRequest;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningResponse;
import com.smartTriage.smartTriage_server.module.isolation.engine.InfectionScreeningEngine;
import com.smartTriage.smartTriage_server.module.isolation.engine.InfectionScreeningEngine.InfectionScreeningResult;
import com.smartTriage.smartTriage_server.module.isolation.engine.InfectionScreeningEngine.PpeRequirements;
import com.smartTriage.smartTriage_server.module.isolation.entity.InfectionScreening;
import com.smartTriage.smartTriage_server.module.isolation.mapper.InfectionScreeningMapper;
import com.smartTriage.smartTriage_server.module.isolation.repository.InfectionScreeningRepository;
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
 * InfectionIsolationService — manages infection screening, isolation, and public health notification.
 *
 * On HIGH_RISK or CONFIRMED: creates CRITICAL clinical alert + mandates PPE.
 * On notifiable disease: creates alert requiring public health notification within 24 hours
 * (per Rwanda IDSR — Integrated Disease Surveillance and Response).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InfectionIsolationService {

    private final InfectionScreeningRepository screeningRepository;
    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final InfectionScreeningEngine screeningEngine;

    /**
     * Run infection screening for a visit.
     * Creates screening record, generates alerts for high-risk cases.
     */
    @Transactional
    public InfectionScreeningResponse screenPatient(UUID visitId, InfectionScreeningRequest request) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));

        TriageRecord triage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No triage record found for visit: " + visitId));

        Instant now = Instant.now();

        // Run screening engine
        InfectionScreeningResult result = screeningEngine.screenPatient(visit, triage, request);
        PpeRequirements ppe = result.ppeRequirements();

        // Create screening entity
        InfectionScreening screening = InfectionScreening.builder()
                .visit(visit)
                .screenedAt(now)
                .screenedByName(request.getScreenedByName())
                .riskLevel(result.riskLevel())
                .isolationType(result.isolationType())
                .suspectedCondition(result.suspectedCondition())
                .notifiableDisease(result.notifiableDisease())
                .hasFever(request.isHasFever())
                .hasCough(request.isHasCough())
                .hasCoughDurationWeeks(request.getHasCoughDurationWeeks())
                .hasNightSweats(request.isHasNightSweats())
                .hasWeightLoss(request.isHasWeightLoss())
                .hasRash(request.isHasRash())
                .hasDiarrhea(request.isHasDiarrhea())
                .hasRecentTravel(request.isHasRecentTravel())
                .recentTravelLocation(request.getRecentTravelLocation())
                .hasContactWithInfectious(request.isHasContactWithInfectious())
                .contactDetails(request.getContactDetails())
                .hasBleedingSymptoms(request.isHasBleedingSymptoms())
                .isHealthcareWorker(request.isHealthcareWorker())
                .requiresN95(ppe.requiresN95)
                .requiresGown(ppe.requiresGown)
                .requiresGloves(ppe.requiresGloves)
                .requiresFaceShield(ppe.requiresFaceShield)
                .requiresApron(ppe.requiresApron)
                .requiresBootCovers(ppe.requiresBootCovers)
                .notes(request.getNotes())
                .build();

        // If isolation required, mark start
        if (result.isolationType() != null) {
            screening.setIsolationStartedAt(now);
        }

        screening = screeningRepository.save(screening);

        // Generate alerts for high-risk or confirmed cases
        if (result.riskLevel() == InfectionRiskLevel.CONFIRMED
                || result.riskLevel() == InfectionRiskLevel.HIGH_RISK) {
            generateInfectionAlert(visit, result);
        }

        // Generate alert for notifiable disease requiring public health notification
        if (result.notifiableDisease() != null) {
            generateNotifiableDiseaseAlert(visit, result);
        }

        log.info("Infection screening completed: visit={}, riskLevel={}, isolation={}, notifiable={}",
                visitId, result.riskLevel(), result.isolationType(), result.notifiableDisease());

        return InfectionScreeningMapper.toResponse(screening, result.findings());
    }

    /**
     * Assign an isolation room to a screening.
     */
    @Transactional
    public InfectionScreening assignIsolationRoom(UUID screeningId, String roomNumber) {
        InfectionScreening screening = screeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("InfectionScreening", "id", screeningId));

        screening.setIsolationRoomAssigned(roomNumber);
        if (screening.getIsolationStartedAt() == null) {
            screening.setIsolationStartedAt(Instant.now());
        }

        screening = screeningRepository.save(screening);

        log.info("Isolation room assigned: screening={}, room={}", screeningId, roomNumber);
        return screening;
    }

    /**
     * End isolation for a screening.
     */
    @Transactional
    public InfectionScreening endIsolation(UUID screeningId) {
        InfectionScreening screening = screeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("InfectionScreening", "id", screeningId));

        screening.setIsolationEndedAt(Instant.now());

        screening = screeningRepository.save(screening);

        log.info("Isolation ended: screening={}", screeningId);
        return screening;
    }

    /**
     * Mark public health notification as sent (to Rwanda RBC).
     */
    @Transactional
    public InfectionScreening notifyPublicHealth(UUID screeningId, String referenceNumber) {
        InfectionScreening screening = screeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("InfectionScreening", "id", screeningId));

        screening.setPublicHealthNotifiedAt(Instant.now());
        screening.setPublicHealthReferenceNumber(referenceNumber);

        screening = screeningRepository.save(screening);

        log.info("Public health notified: screening={}, reference={}", screeningId, referenceNumber);
        return screening;
    }

    /**
     * Active isolations for a hospital, optionally filtered by ED zone.
     */
    public List<InfectionScreening> getActiveIsolations(UUID hospitalId,
                                                        com.smartTriage.smartTriage_server.common.enums.EdZone zone) {
        List<InfectionScreening> all = screeningRepository.findActiveIsolationsByHospital(hospitalId);
        if (zone == null) return all;
        return all.stream()
                .filter(s -> s.getVisit() != null && s.getVisit().getCurrentEdZone() == zone)
                .toList();
    }

    /** Back-compat overload — full hospital-wide list. */
    public List<InfectionScreening> getActiveIsolations(UUID hospitalId) {
        return getActiveIsolations(hospitalId, null);
    }

    /**
     * Get all screenings for a visit.
     */
    public List<InfectionScreening> getScreeningsForVisit(UUID visitId) {
        return screeningRepository.findByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(visitId);
    }

    /**
     * Get all notifiable disease cases for a hospital.
     */
    public List<InfectionScreening> getNotifiableDiseases(UUID hospitalId) {
        return screeningRepository.findNotifiableDiseasesByHospital(hospitalId);
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private void generateInfectionAlert(Visit visit, InfectionScreeningResult result) {
        AlertSeverity severity = result.riskLevel() == InfectionRiskLevel.CONFIRMED
                ? AlertSeverity.CRITICAL : AlertSeverity.HIGH;

        String title = String.format("INFECTION %s: %s — %s isolation required",
                result.riskLevel().name(),
                result.suspectedCondition() != null ? result.suspectedCondition() : "Unknown",
                result.isolationType() != null ? result.isolationType().name() : "Standard");

        String message = String.format(
                "Infection screening for visit %s: Risk level %s. Suspected: %s. " +
                        "Isolation type: %s. PPE required: N95=%s, Gown=%s, Gloves=%s, FaceShield=%s. " +
                        "Findings: %s",
                visit.getVisitNumber(),
                result.riskLevel().name(),
                result.suspectedCondition(),
                result.isolationType(),
                result.ppeRequirements().requiresN95,
                result.ppeRequirements().requiresGown,
                result.ppeRequirements().requiresGloves,
                result.ppeRequirements().requiresFaceShield,
                String.join("; ", result.findings()));

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
        log.info("{} infection alert generated: visit={}", severity, visit.getId());
    }

    private void generateNotifiableDiseaseAlert(Visit visit, InfectionScreeningResult result) {
        String title = String.format("NOTIFIABLE DISEASE: %s — Public health notification required",
                result.notifiableDisease().name().replace("_", " "));

        String message = String.format(
                "Notifiable disease detected for visit %s: %s. " +
                        "Per Rwanda IDSR protocol, Rwanda Biomedical Centre (RBC) must be notified within 24 hours. " +
                        "Risk level: %s. Immediate isolation and contact tracing may be required.",
                visit.getVisitNumber(),
                result.notifiableDisease().name().replace("_", " "),
                result.riskLevel().name());

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.VITAL_SIGN_ABNORMAL)
                .severity(AlertSeverity.CRITICAL)
                .title(title)
                .message(message)
                .autoGenerated(true)
                .escalationTier(1)
                .build();

        clinicalAlertRepository.save(alert);
        log.info("Notifiable disease alert generated: visit={}, disease={}",
                visit.getId(), result.notifiableDisease());
    }
}
