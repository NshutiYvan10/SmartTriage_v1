package com.smartTriage.smartTriage_server.module.isolation.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.InfectionRiskLevel;
import com.smartTriage.smartTriage_server.common.enums.IsolationType;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningRequest;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningResponse;
import com.smartTriage.smartTriage_server.module.isolation.engine.InfectionScreeningEngine;
import com.smartTriage.smartTriage_server.module.isolation.engine.InfectionScreeningEngine.InfectionScreeningResult;
import com.smartTriage.smartTriage_server.module.isolation.engine.InfectionScreeningEngine.PpeRequirements;
import com.smartTriage.smartTriage_server.module.isolation.entity.InfectionScreening;
import com.smartTriage.smartTriage_server.module.isolation.mapper.InfectionScreeningMapper;
import com.smartTriage.smartTriage_server.module.isolation.repository.InfectionScreeningRepository;
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
 * InfectionIsolationService — infection screening, OWNED real-time isolation
 * alerting, room placement, de-isolation, and public-health notification.
 *
 * On a flagged isolation need the service raises a dedicated, ZONE-OWNED
 * {@code ISOLATION_REQUIRED} alert (zone doctor + charge nurse for bed/zone
 * reassignment), pushed in real time, plus a {@code NOTIFIABLE_DISEASE} alert
 * for Rwanda-IDSR reportable conditions. A placement clock ({@code placementDueAt})
 * drives the {@code IsolationPlacementMonitorService} escalation. Re-screening
 * can never silently DOWNGRADE an active precaution — a stricter prior open
 * isolation is carried forward and superseded, never lowered; de-isolation is an
 * explicit, actor-stamped action.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InfectionIsolationService {

    /** Window within which a flagged patient must be in an isolation room before escalation. */
    static final Duration PLACEMENT_WINDOW = Duration.ofMinutes(30);

    private final InfectionScreeningRepository screeningRepository;
    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final InfectionScreeningEngine screeningEngine;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final ShiftAssignmentService shiftAssignmentService;

    /**
     * Run infection screening for a visit. Creates the screening record, raises
     * owned real-time alerts for high-risk / notifiable cases, and supersedes any
     * prior open isolation for the visit (carrying the strictest precaution forward).
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
        InfectionScreeningResult result = screeningEngine.screenPatient(visit, triage, request);
        PpeRequirements ppe = result.ppeRequirements();

        // Actor is the authenticated user; fall back to the (optional) request name.
        String actor = resolveCurrentUserName();
        if (actor == null) actor = request.getScreenedByName();

        // Never DOWNGRADE on re-screen: carry the strictest of any prior OPEN isolation
        // forward into this screening, so only an explicit end-isolation can lower it.
        // Also carry forward an existing ROOM PLACEMENT + the original start time, so a
        // re-screen of an already-isolated patient does not lose the room or falsely
        // re-arm the placement clock (which would page a spurious placement-overdue alert).
        List<InfectionScreening> priorOpen = screeningRepository.findOpenIsolationsForVisit(visitId);
        IsolationType effectiveType = result.isolationType();
        InfectionRiskLevel effectiveRisk = result.riskLevel();
        String carriedRoom = null;
        Instant carriedRoomAt = null;
        String carriedAssignedBy = null;
        Instant carriedStartedAt = null;
        if (effectiveType != null) {
            for (InfectionScreening p : priorOpen) {
                effectiveType = InfectionScreeningEngine.strictest(effectiveType, p.getIsolationType());
                effectiveRisk = InfectionScreeningEngine.maxRisk(effectiveRisk, p.getRiskLevel());
                if (p.getIsolationRoomAssigned() != null && carriedRoom == null) {
                    carriedRoom = p.getIsolationRoomAssigned();
                    carriedRoomAt = p.getIsolationRoomAssignedAt();
                    carriedAssignedBy = p.getIsolationAssignedByName();
                }
                if (p.getIsolationStartedAt() != null
                        && (carriedStartedAt == null || p.getIsolationStartedAt().isBefore(carriedStartedAt))) {
                    carriedStartedAt = p.getIsolationStartedAt();
                }
            }
        }

        InfectionScreening screening = InfectionScreening.builder()
                .visit(visit)
                .screenedAt(now)
                .screenedByName(actor)
                .riskLevel(effectiveRisk)
                .isolationType(effectiveType)
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
                .immunocompromised(request.isImmunocompromised())
                .hasNeckStiffness(request.isHasNeckStiffness())
                .isolationRoomAssigned(carriedRoom)
                .isolationRoomAssignedAt(carriedRoomAt)
                .isolationAssignedByName(carriedAssignedBy)
                .requiresN95(ppe.requiresN95)
                .requiresGown(ppe.requiresGown)
                .requiresGloves(ppe.requiresGloves)
                .requiresFaceShield(ppe.requiresFaceShield)
                .requiresApron(ppe.requiresApron)
                .requiresBootCovers(ppe.requiresBootCovers)
                .notes(request.getNotes())
                .build();

        // Isolation required → preserve the original start time; only arm the placement
        // clock when the patient is NOT already in an isolation room (carried forward).
        if (effectiveType != null) {
            screening.setIsolationStartedAt(carriedStartedAt != null ? carriedStartedAt : now);
            if (carriedRoom == null) {
                screening.setPlacementDueAt(now.plus(PLACEMENT_WINDOW));
            }
        }

        screening = screeningRepository.save(screening);

        // Supersede prior open isolations so a visit never carries two active precautions.
        if (effectiveType != null && !priorOpen.isEmpty()) {
            for (InfectionScreening p : priorOpen) {
                p.setIsolationEndedAt(now);
                p.setIsolationEndedByName(actor);
                p.setIsolationEndReason("Superseded by re-screen " + screening.getId());
                screeningRepository.save(p);
            }
        }

        // Page staff whenever isolation is FIRST required for this visit (any precaution —
        // incl. PROTECTIVE / plain-CONTACT, which sit at low risk but still need placement),
        // and on any high-risk assessment. A benign carry-forward re-screen of an
        // already-isolated patient (priorOpen non-empty, low new risk) does NOT re-page.
        boolean newlyRequiresIsolation = effectiveType != null && priorOpen.isEmpty();
        boolean highRisk = result.riskLevel() == InfectionRiskLevel.CONFIRMED
                || result.riskLevel() == InfectionRiskLevel.HIGH_RISK;
        if (effectiveType != null && (newlyRequiresIsolation || highRisk)) {
            generateInfectionAlert(visit, result, effectiveType, effectiveRisk);
        }
        if (result.notifiableDisease() != null) {
            generateNotifiableDiseaseAlert(visit, result);
        }

        publishIsolationDashboard(visit, "SCREENED");
        log.info("Infection screening completed: visit={}, riskLevel={}, isolation={}, notifiable={}",
                visitId, effectiveRisk, effectiveType, result.notifiableDisease());

        return InfectionScreeningMapper.toResponse(screening, result.findings());
    }

    /** Assign an isolation room — records the room, the actor, and the time; stops the placement clock. */
    @Transactional
    public InfectionScreening assignIsolationRoom(UUID screeningId, String roomNumber) {
        InfectionScreening screening = screeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("InfectionScreening", "id", screeningId));

        Instant now = Instant.now();
        screening.setIsolationRoomAssigned(roomNumber);
        screening.setIsolationRoomAssignedAt(now);
        screening.setIsolationAssignedByName(resolveCurrentUserName());
        if (screening.getIsolationStartedAt() == null) {
            screening.setIsolationStartedAt(now);
        }
        screening.setPlacementDueAt(null); // placed — no longer placement-overdue eligible
        screening = screeningRepository.save(screening);

        publishIsolationDashboard(screening.getVisit(), "ROOM_ASSIGNED");
        log.info("Isolation room assigned: screening={}, room={}, by={}",
                screeningId, roomNumber, screening.getIsolationAssignedByName());
        return screening;
    }

    /** End / clear isolation — explicit, actor-stamped, with a mandatory reason (de-isolation). */
    @Transactional
    public InfectionScreening endIsolation(UUID screeningId, String reason) {
        InfectionScreening screening = screeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("InfectionScreening", "id", screeningId));

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A clearance reason is required to end isolation "
                    + "(e.g. lab-confirmed exclusion, criteria no longer met).");
        }

        screening.setIsolationEndedAt(Instant.now());
        screening.setIsolationEndedByName(resolveCurrentUserName());
        screening.setIsolationEndReason(reason);
        screening.setPlacementDueAt(null);
        screening = screeningRepository.save(screening);

        publishIsolationDashboard(screening.getVisit(), "CLEARED");
        log.info("Isolation ended: screening={}, by={}, reason={}",
                screeningId, screening.getIsolationEndedByName(), reason);
        return screening;
    }

    /** Mark public health notification as sent (to Rwanda RBC) — records the actor. */
    @Transactional
    public InfectionScreening notifyPublicHealth(UUID screeningId, String referenceNumber) {
        InfectionScreening screening = screeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("InfectionScreening", "id", screeningId));

        screening.setPublicHealthNotifiedAt(Instant.now());
        screening.setPublicHealthReferenceNumber(referenceNumber);
        screening.setPublicHealthNotifiedByName(resolveCurrentUserName());
        screening = screeningRepository.save(screening);

        publishIsolationDashboard(screening.getVisit(), "NOTIFIED");
        log.info("Public health notified: screening={}, reference={}, by={}",
                screeningId, referenceNumber, screening.getPublicHealthNotifiedByName());
        return screening;
    }

    public List<InfectionScreening> getActiveIsolations(UUID hospitalId, EdZone zone) {
        List<InfectionScreening> all = screeningRepository.findActiveIsolationsByHospital(hospitalId);
        if (zone == null) return all;
        return all.stream()
                .filter(s -> s.getVisit() != null && s.getVisit().getCurrentEdZone() == zone)
                .toList();
    }

    public List<InfectionScreening> getActiveIsolations(UUID hospitalId) {
        return getActiveIsolations(hospitalId, null);
    }

    public List<InfectionScreening> getScreeningsForVisit(UUID visitId) {
        return screeningRepository.findByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(visitId);
    }

    public List<InfectionScreening> getNotifiableDiseases(UUID hospitalId) {
        return screeningRepository.findNotifiableDiseasesByHospital(hospitalId);
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private void generateInfectionAlert(Visit visit, InfectionScreeningResult result,
                                        IsolationType effectiveType, InfectionRiskLevel effectiveRisk) {
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = resolveZoneDoctor(hospitalId, zone);
        AlertSeverity severity = switch (effectiveRisk) {
            case CONFIRMED -> AlertSeverity.CRITICAL;
            case HIGH_RISK -> AlertSeverity.HIGH;
            default -> AlertSeverity.MEDIUM;
        };

        String title = String.format("ISOLATION REQUIRED (%s): %s — %s precautions",
                effectiveRisk.name(),
                result.suspectedCondition() != null ? result.suspectedCondition() : "Suspected infection",
                effectiveType != null ? effectiveType.name() : "Standard");

        String message = String.format(
                "Infection screening for %s (Visit: %s): risk %s, suspected %s. %s isolation required — "
                + "place in an appropriate isolation room. PPE: N95=%s, Gown=%s, Gloves=%s, FaceShield=%s, "
                + "Apron=%s, BootCovers=%s. Findings: %s",
                patientName(visit), visit.getVisitNumber(), effectiveRisk.name(),
                result.suspectedCondition(), effectiveType,
                result.ppeRequirements().requiresN95, result.ppeRequirements().requiresGown,
                result.ppeRequirements().requiresGloves, result.ppeRequirements().requiresFaceShield,
                result.ppeRequirements().requiresApron, result.ppeRequirements().requiresBootCovers,
                String.join("; ", result.findings()));

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.ISOLATION_REQUIRED)
                .severity(severity)
                .title(title)
                .message(message)
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .escalationTier(1)
                .build();
        alert = clinicalAlertRepository.save(alert);
        publishOwnedAlert(alert, hospitalId, zone, zoneDoctor);
        log.warn("{} ISOLATION_REQUIRED alert generated: visit={}, zone={}, doctor={}",
                severity, visit.getId(), zone, zoneDoctor != null ? zoneDoctor.getId() : "unassigned");
    }

    private void generateNotifiableDiseaseAlert(Visit visit, InfectionScreeningResult result) {
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = resolveZoneDoctor(hospitalId, zone);

        String disease = result.notifiableDisease().name().replace("_", " ");
        String title = String.format("NOTIFIABLE DISEASE: %s — RBC notification required", disease);
        String message = String.format(
                "Notifiable disease suspected for %s (Visit: %s): %s. Per Rwanda IDSR, Rwanda Biomedical "
                + "Centre (RBC) must be notified within 24 hours. Risk: %s. Isolation + contact tracing may be required.",
                patientName(visit), visit.getVisitNumber(), disease, result.riskLevel().name());

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.NOTIFIABLE_DISEASE)
                .severity(AlertSeverity.CRITICAL)
                .title(title)
                .message(message)
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .escalationTier(1)
                .build();
        alert = clinicalAlertRepository.save(alert);
        publishOwnedAlert(alert, hospitalId, zone, zoneDoctor);
        log.warn("NOTIFIABLE_DISEASE alert generated: visit={}, disease={}", visit.getId(), result.notifiableDisease());
    }

    /** Push the alert to the zone board + zone doctor + charge nurse(s) AFTER COMMIT (best-effort). */
    private void publishOwnedAlert(ClinicalAlert alert, UUID hospitalId, EdZone zone, User zoneDoctor) {
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
                log.warn("Failed to publish isolation alert {}: {}", alertId, e.getMessage());
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

    private void publishIsolationDashboard(Visit visit, String eventType) {
        try {
            if (visit == null) return;
            UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
            if (hospitalId != null) {
                realTimeEventPublisher.publishIsolationEventAfterCommit(hospitalId, Map.of(
                        "eventType", eventType,
                        "visitId", visit.getId().toString()));
            }
        } catch (Exception e) {
            log.warn("Failed to publish isolation dashboard event: {}", e.getMessage());
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
            // no resolvable principal (scheduled / system context)
        }
        return null;
    }
}
