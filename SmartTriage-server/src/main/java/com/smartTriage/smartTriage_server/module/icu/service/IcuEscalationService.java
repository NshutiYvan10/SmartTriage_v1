package com.smartTriage.smartTriage_server.module.icu.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.icu.dto.IcuCapacityResponse;
import com.smartTriage.smartTriage_server.module.icu.dto.IcuEscalationRequest;
import com.smartTriage.smartTriage_server.module.icu.dto.IcuResponseRequest;
import com.smartTriage.smartTriage_server.module.icu.engine.IcuEscalationEngine;
import com.smartTriage.smartTriage_server.module.icu.engine.IcuEscalationEngine.IcuEscalationRecommendation;
import com.smartTriage.smartTriage_server.module.icu.entity.IcuEscalation;
import com.smartTriage.smartTriage_server.module.icu.repository.IcuEscalationRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * IcuEscalationService — orchestrates the full ICU escalation lifecycle.
 *
 * Handles manual and automatic escalation requests, ICU team notification,
 * response recording, bed assignment, transfer, and cancellation.
 *
 * In Rwanda's resource-constrained setting, this service ensures proper
 * documentation and generates clinical alerts when ICU beds are unavailable,
 * prompting the clinical team to arrange referral to a higher-level facility.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IcuEscalationService {

    private final IcuEscalationRepository icuEscalationRepository;
    private final VisitRepository visitRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final HospitalRepository hospitalRepository;
    private final IcuEscalationEngine icuEscalationEngine;

    /**
     * Create a manual ICU escalation request.
     * Generates a CRITICAL clinical alert for the ICU team.
     */
    @Transactional
    public IcuEscalation requestEscalation(IcuEscalationRequest request) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(request.getVisitId())
                .orElseThrow(() -> new IllegalArgumentException("Visit not found: " + request.getVisitId()));

        // Prevent duplicate active escalations
        if (icuEscalationRepository.existsActiveEscalationForVisit(visit.getId())) {
            throw new IllegalStateException("An active ICU escalation already exists for visit: " + visit.getVisitNumber());
        }

        IcuEscalation escalation = IcuEscalation.builder()
                .visit(visit)
                .escalationReason(request.getEscalationReason())
                .triggerType(request.getTriggerType() != null ? request.getTriggerType() : IcuTriggerType.CLINICAL_JUDGEMENT)
                .escalatedAt(Instant.now())
                .isAutomatic(false)
                .status(IcuEscalationStatus.REQUESTED)
                .build();

        escalation = icuEscalationRepository.save(escalation);

        // Generate CRITICAL clinical alert
        generateEscalationAlert(visit, escalation);

        log.info("ICU escalation requested: Visit {} | Trigger: {} | Reason: {}",
                visit.getVisitNumber(), escalation.getTriggerType(), request.getEscalationReason());

        return escalation;
    }

    /**
     * Auto-evaluate a visit for ICU escalation using the ICU engine.
     * Called by the scheduled auto-detection service or manually triggered.
     *
     * @return the created escalation if ICU is recommended, empty otherwise
     */
    @Transactional
    public Optional<IcuEscalation> autoEvaluate(UUID visitId) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Visit not found: " + visitId));

        // Skip if already has an active escalation
        if (icuEscalationRepository.existsActiveEscalationForVisit(visitId)) {
            log.debug("Skipping auto-evaluation for visit {} — active escalation exists", visit.getVisitNumber());
            return Optional.empty();
        }

        // Get latest vitals
        Optional<VitalSigns> latestVitals = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId);

        if (latestVitals.isEmpty()) {
            log.debug("No vitals available for auto-evaluation: Visit {}", visit.getVisitNumber());
            return Optional.empty();
        }

        // Run the ICU escalation engine
        IcuEscalationRecommendation recommendation = icuEscalationEngine.evaluate(latestVitals.get());

        if (!recommendation.icuRecommended()) {
            return Optional.empty();
        }

        // Create automatic escalation
        IcuEscalation escalation = IcuEscalation.builder()
                .visit(visit)
                .escalationReason(recommendation.reasoning())
                .triggerType(recommendation.triggerType())
                .escalatedAt(Instant.now())
                .isAutomatic(true)
                .status(IcuEscalationStatus.REQUESTED)
                .build();

        escalation = icuEscalationRepository.save(escalation);

        // Generate CRITICAL clinical alert
        generateEscalationAlert(visit, escalation);

        log.warn("AUTO ICU ESCALATION: Visit {} | Trigger: {} | Reasoning: {}",
                visit.getVisitNumber(), recommendation.triggerType(), recommendation.reasoning());

        return Optional.of(escalation);
    }

    /**
     * Record that the ICU team has been notified.
     */
    @Transactional
    public IcuEscalation notifyIcuTeam(UUID escalationId) {
        IcuEscalation escalation = findActiveEscalation(escalationId);

        escalation.setIcuTeamNotifiedAt(Instant.now());
        escalation.setStatus(IcuEscalationStatus.ICU_NOTIFIED);

        log.info("ICU team notified for escalation: {} | Visit: {}",
                escalationId, escalation.getVisit().getVisitNumber());

        return icuEscalationRepository.save(escalation);
    }

    /**
     * Record the ICU team's response (accept or decline).
     * If declined, generates an ICU_BED_UNAVAILABLE alert suggesting referral.
     */
    @Transactional
    public IcuEscalation recordResponse(UUID escalationId, IcuResponseRequest request) {
        IcuEscalation escalation = findActiveEscalation(escalationId);

        escalation.setIcuRespondedAt(Instant.now());

        // Calculate response time in minutes
        if (escalation.getIcuTeamNotifiedAt() != null) {
            long responseMinutes = Duration.between(escalation.getIcuTeamNotifiedAt(), Instant.now()).toMinutes();
            escalation.setIcuResponseMinutes((int) responseMinutes);
        }

        if (request.isAccepted()) {
            escalation.setStatus(IcuEscalationStatus.ICU_ACCEPTED);
            escalation.setIcuBedAvailable(true);

            if (request.getBedNumber() != null && !request.getBedNumber().isBlank()) {
                escalation.setIcuBedNumber(request.getBedNumber());
                escalation.setIcuBedAssignedAt(Instant.now());
            }

            log.info("ICU escalation ACCEPTED: {} | Visit: {} | Bed: {}",
                    escalationId, escalation.getVisit().getVisitNumber(), request.getBedNumber());
        } else {
            escalation.setStatus(IcuEscalationStatus.ICU_DECLINED);
            escalation.setIcuBedAvailable(false);
            escalation.setDeclineReason(request.getDeclineReason());

            // Generate ICU_BED_UNAVAILABLE alert suggesting referral
            generateBedUnavailableAlert(escalation);

            log.warn("ICU escalation DECLINED: {} | Visit: {} | Reason: {}",
                    escalationId, escalation.getVisit().getVisitNumber(), request.getDeclineReason());
        }

        return icuEscalationRepository.save(escalation);
    }

    /**
     * Assign an ICU bed to the escalation.
     */
    @Transactional
    public IcuEscalation assignBed(UUID escalationId, String bedNumber) {
        IcuEscalation escalation = findActiveEscalation(escalationId);

        escalation.setIcuBedNumber(bedNumber);
        escalation.setIcuBedAvailable(true);
        escalation.setIcuBedAssignedAt(Instant.now());

        if (escalation.getStatus() == IcuEscalationStatus.REQUESTED
                || escalation.getStatus() == IcuEscalationStatus.ICU_NOTIFIED) {
            escalation.setStatus(IcuEscalationStatus.ICU_ACCEPTED);
        }

        log.info("ICU bed assigned: {} | Escalation: {} | Visit: {}",
                bedNumber, escalationId, escalation.getVisit().getVisitNumber());

        return icuEscalationRepository.save(escalation);
    }

    /**
     * Mark the patient as transferred to ICU and update the visit status.
     */
    @Transactional
    public IcuEscalation transferToIcu(UUID escalationId) {
        IcuEscalation escalation = findActiveEscalation(escalationId);

        escalation.setStatus(IcuEscalationStatus.TRANSFERRED_TO_ICU);
        escalation.setTransferredAt(Instant.now());

        // Update visit status to ICU_ADMITTED
        Visit visit = escalation.getVisit();
        visit.setStatus(VisitStatus.ICU_ADMITTED);
        visitRepository.save(visit);

        log.info("Patient transferred to ICU: Visit {} | Bed: {}",
                visit.getVisitNumber(), escalation.getIcuBedNumber());

        return icuEscalationRepository.save(escalation);
    }

    /**
     * Cancel an escalation with a reason.
     */
    @Transactional
    public IcuEscalation cancelEscalation(UUID escalationId, String reason) {
        IcuEscalation escalation = findActiveEscalation(escalationId);

        escalation.setStatus(IcuEscalationStatus.CANCELLED);
        escalation.setNotes(reason);

        log.info("ICU escalation cancelled: {} | Visit: {} | Reason: {}",
                escalationId, escalation.getVisit().getVisitNumber(), reason);

        return icuEscalationRepository.save(escalation);
    }

    /**
     * Get paginated active (non-terminal) escalations for a hospital.
     */
    public Page<IcuEscalation> getActiveEscalations(UUID hospitalId, Pageable pageable) {
        return icuEscalationRepository.findActiveEscalationsByHospital(hospitalId, pageable);
    }

    /**
     * Get the active escalation for a specific visit.
     */
    public Optional<IcuEscalation> getEscalationForVisit(UUID visitId) {
        return icuEscalationRepository.findByVisitIdAndIsActiveTrue(visitId);
    }

    /**
     * Calculate ICU bed capacity for a hospital.
     * Total ICU beds come from the hospital entity; occupied beds are visits with ICU_ADMITTED status.
     */
    public IcuCapacityResponse getIcuCapacity(UUID hospitalId) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new IllegalArgumentException("Hospital not found: " + hospitalId));

        int totalBeds = hospital.getIcuCapacity() != null ? hospital.getIcuCapacity() : 0;

        // Count visits currently admitted to ICU at this hospital
        long occupiedCount = visitRepository.findByHospitalIdAndStatus(hospitalId, VisitStatus.ICU_ADMITTED,
                Pageable.unpaged()).getTotalElements();
        int occupiedBeds = (int) occupiedCount;

        int availableBeds = Math.max(0, totalBeds - occupiedBeds);
        double occupancyPercent = totalBeds > 0 ? (double) occupiedBeds / totalBeds * 100.0 : 0.0;

        return IcuCapacityResponse.builder()
                .totalBeds(totalBeds)
                .occupiedBeds(occupiedBeds)
                .availableBeds(availableBeds)
                .occupancyPercent(occupancyPercent)
                .build();
    }

    // --- Private helpers ---

    private IcuEscalation findActiveEscalation(UUID escalationId) {
        return icuEscalationRepository.findByIdAndIsActiveTrue(escalationId)
                .orElseThrow(() -> new IllegalArgumentException("ICU escalation not found: " + escalationId));
    }

    private void generateEscalationAlert(Visit visit, IcuEscalation escalation) {
        String patientName = "";
        if (visit.getPatient() != null) {
            patientName = visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();
        }

        String autoLabel = escalation.isAutomatic() ? " [AUTO-DETECTED]" : "";

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.ICU_ESCALATION_REQUESTED)
                .severity(AlertSeverity.CRITICAL)
                .title("ICU ESCALATION REQUESTED" + autoLabel)
                .message(String.format(
                        "CRITICAL: ICU escalation requested for patient %s (Visit: %s). " +
                        "Trigger: %s. Reason: %s. " +
                        "IMMEDIATE ACTION REQUIRED: Notify ICU team and begin stabilization.",
                        patientName,
                        visit.getVisitNumber(),
                        escalation.getTriggerType(),
                        escalation.getEscalationReason()))
                .autoGenerated(escalation.isAutomatic())
                .targetZone(EdZone.RESUS)
                .build();

        clinicalAlertRepository.save(alert);
    }

    private void generateBedUnavailableAlert(IcuEscalation escalation) {
        Visit visit = escalation.getVisit();
        String patientName = "";
        if (visit.getPatient() != null) {
            patientName = visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();
        }

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.ICU_BED_UNAVAILABLE)
                .severity(AlertSeverity.CRITICAL)
                .title("ICU BED UNAVAILABLE — CONSIDER REFERRAL")
                .message(String.format(
                        "CRITICAL: ICU escalation for patient %s (Visit: %s) was DECLINED. " +
                        "Reason: %s. " +
                        "No ICU bed available at this facility. " +
                        "ACTION REQUIRED: Consider referral to a higher-level facility with ICU capacity. " +
                        "Continue stabilization in ED resuscitation area until transfer arranged.",
                        patientName,
                        visit.getVisitNumber(),
                        escalation.getDeclineReason() != null ? escalation.getDeclineReason() : "Not specified"))
                .autoGenerated(true)
                .targetZone(EdZone.RESUS)
                .build();

        clinicalAlertRepository.save(alert);
    }
}
