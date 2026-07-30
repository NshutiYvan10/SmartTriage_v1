package com.smartTriage.smartTriage_server.module.isolation.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.InfectionRiskLevel;
import com.smartTriage.smartTriage_server.common.enums.IsolationType;
import com.smartTriage.smartTriage_server.common.enums.NotifiableDisease;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.bed.dto.PlacePatientRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.TransferPatientRequest;
import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
import com.smartTriage.smartTriage_server.module.bed.repository.BedRepository;
import com.smartTriage.smartTriage_server.module.bed.service.BedService;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Objective fever threshold (°C) — a measured triage temperature at/above this is a fever fact. */
    static final double FEVER_THRESHOLD_C = 38.0;

    private final InfectionScreeningRepository screeningRepository;
    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final InfectionScreeningEngine screeningEngine;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final ShiftAssignmentService shiftAssignmentService;
    /** Self-reference through the Spring proxy — the post-commit enforcement halves
     *  ({@code autoScreenFromTriage} / {@code raiseScreeningRequired}) must start their
     *  own transactions, which a plain {@code this} call would silently skip. */
    private final ObjectProvider<InfectionIsolationService> self;
    /** Isolation room assignment now physically relocates the patient into an
     *  ISOLATION bed. BedService is taken via ObjectProvider (lazy) mirroring the
     *  {@code self} pattern, so there is no construction-order coupling to the bed
     *  module; BedRepository is a plain repository and is injected directly. */
    private final BedRepository bedRepository;
    private final ObjectProvider<BedService> bedServiceProvider;

    /**
     * Run infection screening for a visit. Creates the screening record, raises
     * owned real-time alerts for high-risk / notifiable cases, and supersedes any
     * prior open isolation for the visit (carrying the strictest precaution forward).
     */
    @Transactional
    public InfectionScreeningResponse screenPatient(UUID visitId, InfectionScreeningRequest request) {
        return screenPatientInternal(visitId, request, null);
    }

    /**
     * Shared screening pipeline. {@code actorOverride} lets the triage auto path stamp
     * "&lt;nurse&gt; (auto: triage red flags)" as the screener; manual calls pass null and
     * keep the authenticated-user resolution.
     */
    private InfectionScreeningResponse screenPatientInternal(UUID visitId, InfectionScreeningRequest request,
                                                             String actorOverride) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));

        // Triage is OPTIONAL: infection risk is often flagged at the door, before triage
        // (previously this threw 404 and door-time screening was impossible). The engine
        // and the derivation below are null-safe on a missing record.
        TriageRecord triage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId)
                .orElse(null);

        // Objective triage facts override subjective checkbox omissions: a clinician
        // cannot understate a MEASURED 39 °C fever (or a structured hemoptysis /
        // paediatric-diarrhoea flag) by leaving a box unticked.
        applyTriageDerivedFacts(request, triage);

        Instant now = Instant.now();
        InfectionScreeningResult result = screeningEngine.screenPatient(visit, triage, request);
        PpeRequirements ppe = result.ppeRequirements();

        // Actor: explicit override (auto path) > authenticated user > (optional) request name.
        String actor = actorOverride;
        if (actor == null) actor = resolveCurrentUserName();
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

    // ====================================================================
    // FRONT-DOOR ENFORCEMENT — triage hook
    // ====================================================================

    /**
     * Called by {@code TriageService} after every triage/re-triage (best-effort,
     * never throws into the triage flow). Structured triage red flags that ALONE
     * constitute an isolation need — purpuric rash, measured fever + haemorrhagic
     * symptoms, paediatric infectious diarrhoea — auto-file a REAL screening:
     * precaution, PPE, placement clock and owned alerts fire NOW instead of
     * waiting for someone to open the Isolation tab. A fever (or an
     * infectious-sounding complaint) that does NOT amount to a precaution never
     * fabricates a "screened" record; it raises an ISOLATION_SCREENING_REQUIRED
     * prompt for a human screening instead.
     *
     * <p>The DECISION is made here, inside the triage transaction (entities are
     * attached); the WRITES are deferred to AFTER COMMIT and run in a fresh
     * transaction through the Spring proxy. Running the screening pipeline inside
     * the triage transaction and catching its failure would poison the whole
     * triage commit (rollback-only → UnexpectedRollbackException — the bedded-RED
     * re-triage bug pattern); post-commit, an enforcement failure can only ever
     * lose the enforcement, never the triage.
     */
    public void enforceFromTriage(Visit visit, TriageRecord triage) {
        try {
            if (visit == null || triage == null) return;
            final UUID visitId = visit.getId();

            InfectionScreeningRequest derived = new InfectionScreeningRequest();
            applyTriageDerivedFacts(derived, triage);
            // Dry-run — the engine also reads the purpuric-rash flag straight off the triage record.
            InfectionScreeningResult dryRun = screeningEngine.screenPatient(visit, triage, derived);

            boolean flagged = (dryRun.isolationType() != null || dryRun.notifiableDisease() != null)
                    && escalatesBeyondPrior(dryRun, screeningRepository.findOpenIsolationsForVisit(visitId));
            String promptReason = flagged ? null : unexplainedInfectionSuspicion(visit, triage, derived);
            if (!flagged && promptReason == null) return;

            final String actor = resolveCurrentUserName();
            final String reason = promptReason;
            Runnable work = () -> {
                try {
                    if (flagged) {
                        self.getObject().autoScreenFromTriage(visitId, derived, actor);
                    } else {
                        self.getObject().raiseScreeningRequired(visitId, reason);
                    }
                } catch (Exception e) {
                    log.warn("Post-commit isolation enforcement failed for visit {}: {}", visitId, e.getMessage());
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { work.run(); }
                });
            } else {
                work.run();
            }
        } catch (Exception e) {
            log.warn("Isolation triage enforcement failed for visit {}: {}",
                    visit != null ? visit.getId() : null, e.getMessage());
        }
    }

    /**
     * Post-commit half of the auto path. MUST be REQUIRES_NEW: afterCommit callbacks
     * still run with the ORIGINAL (already-committed) transaction's resources bound, so
     * plain REQUIRED would silently join a transaction that will never commit again —
     * every write would execute, log success, and be discarded on connection release.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void autoScreenFromTriage(UUID visitId, InfectionScreeningRequest derived, String actorName) {
        String note = "Auto-screened from triage red flags — complete a full infection screening "
                + "(travel / contact / TB symptoms) to finish the assessment.";
        derived.setNotes(derived.getNotes() == null || derived.getNotes().isBlank()
                ? note : derived.getNotes() + "\n" + note);
        String actor = (actorName != null ? actorName : "SmartTriage") + " (auto: triage red flags)";
        log.debug("autoScreenFromTriage tx-state: active={}, readOnly={}, name={}",
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                TransactionSynchronizationManager.getCurrentTransactionName());
        InfectionScreeningResponse resp = screenPatientInternal(visitId, derived, actor);
        log.warn("AUTO isolation screening from triage: visit={}, isolation={}, notifiable={}",
                visitId, resp.getIsolationType(), resp.getNotifiableDisease());
    }

    /** Post-commit half of the prompt path — REQUIRES_NEW for the same reason as
     *  {@link #autoScreenFromTriage} (afterCommit + REQUIRED = writes silently discarded). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void raiseScreeningRequired(UUID visitId, String reason) {
        log.debug("raiseScreeningRequired tx-state: active={}, readOnly={}, name={}",
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                TransactionSynchronizationManager.getCurrentTransactionName());
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId).orElse(null);
        if (visit == null) return;
        // A screening on file (human or auto) means someone has judged this patient — don't prompt.
        if (screeningRepository.existsByVisitIdAndIsActiveTrue(visitId)) return;
        // An unacknowledged prompt is already on the board — don't stack duplicates.
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.ISOLATION_SCREENING_REQUIRED)) {
            return;
        }

        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = resolveZoneDoctor(hospitalId, zone);
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.ISOLATION_SCREENING_REQUIRED)
                .severity(AlertSeverity.MEDIUM)
                .title("INFECTION SCREENING REQUIRED")
                .message(String.format(
                        "%s (Visit: %s): %s. Run an infection screening (patient chart → Isolation tab) "
                        + "to assess isolation and IDSR notifiable-disease risk.",
                        patientName(visit), visit.getVisitNumber(),
                        reason != null ? reason : "Infection suspicion at triage"))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .escalationTier(1)
                .build();
        alert = clinicalAlertRepository.save(alert);
        publishOwnedAlert(alert, hospitalId, zone, zoneDoctor);
        log.info("ISOLATION_SCREENING_REQUIRED raised: visit={}, reason={}", visitId, reason);
    }

    /**
     * Assign an isolation room — a PHYSICAL relocation, not just a label. Resolves a
     * free ISOLATION bed (preferring one whose code/label matches the requested room),
     * moves the patient into it (transfer from their current bed, or a fresh placement),
     * flips the visit to the ISOLATION zone, and records the room / actor / time while
     * stopping the placement clock. Fails clearly if no isolation bed is free.
     *
     * <p>Previously this only wrote the room string onto the screening record and never
     * touched the visit's zone or bed, so "assign room" left the patient in their
     * original (e.g. OBSERVATION) zone — the move never actually happened.
     */
    @Transactional
    public InfectionScreening assignIsolationRoom(UUID screeningId, String roomNumber) {
        InfectionScreening screening = screeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("InfectionScreening", "id", screeningId));

        Visit visit = screening.getVisit();
        if (visit == null) {
            throw new ClinicalBusinessException("Isolation screening has no associated visit to relocate.");
        }

        // Resolve a free isolation bed FIRST — if there is none we abort before
        // recording anything, so the screening never claims a placement that
        // did not happen.
        UUID hospitalId = visit.getHospital().getId();
        List<Bed> freeIsolation = bedRepository.findAvailableInZone(hospitalId, EdZone.ISOLATION);
        if (freeIsolation.isEmpty()) {
            throw new ClinicalBusinessException(
                    "No isolation bed is currently available. Free or add an ISOLATION bed, "
                            + "then assign the room again.");
        }
        Bed target = matchRequestedIsolationBed(freeIsolation, roomNumber).orElse(freeIsolation.get(0));

        String actor = resolveCurrentUserName();

        // Physically move the patient into the isolation bed, reusing BedService so
        // monitor sessions + bed-change events are handled: transfer if they already
        // occupy a bed, otherwise a fresh placement.
        Bed currentBed = visit.getCurrentBed();
        BedService bedService = bedServiceProvider.getObject();
        if (currentBed == null) {
            bedService.placePatient(target.getId(),
                    PlacePatientRequest.builder().visitId(visit.getId()).build(), actor);
        } else if (!currentBed.getId().equals(target.getId())) {
            bedService.transferPatient(currentBed.getId(),
                    TransferPatientRequest.builder()
                            .destinationBedId(target.getId())
                            .reason("Isolation placement — moved to isolation room")
                            .build(),
                    actor);
        }
        // else: already in the chosen isolation bed — nothing to move.

        // BedService place/transfer set the visit's currentBed but NOT its zone; set it
        // explicitly so the patient reads as ISOLATION everywhere (dashboards, routing, alerts).
        visit.setCurrentEdZone(EdZone.ISOLATION);
        visitRepository.save(visit);

        // Record the room as the actual bed now holding the patient (reflects reality).
        Instant now = Instant.now();
        String roomLabel = (target.getLabel() != null && !target.getLabel().isBlank())
                ? target.getLabel() : target.getCode();
        screening.setIsolationRoomAssigned(roomLabel);
        screening.setIsolationRoomAssignedAt(now);
        screening.setIsolationAssignedByName(actor);
        if (screening.getIsolationStartedAt() == null) {
            screening.setIsolationStartedAt(now);
        }
        screening.setPlacementDueAt(null); // placed — no longer placement-overdue eligible
        screening = screeningRepository.save(screening);

        publishIsolationDashboard(screening.getVisit(), "ROOM_ASSIGNED");
        log.info("Isolation room assigned + patient relocated to ISOLATION: screening={}, bed={}, by={}",
                screeningId, target.getCode(), actor);
        return hydrateForResponse(screening);
    }

    /** Prefer an available isolation bed whose code or label matches the requested room string. */
    private Optional<Bed> matchRequestedIsolationBed(List<Bed> beds, String requested) {
        if (requested == null || requested.isBlank()) return Optional.empty();
        String r = requested.trim();
        return beds.stream()
                .filter(b -> r.equalsIgnoreCase(b.getCode()) || r.equalsIgnoreCase(b.getLabel()))
                .findFirst();
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
        return hydrateForResponse(screening);
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
        return hydrateForResponse(screening);
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

    /**
     * OR structured triage facts into the screening request — objective data can only
     * ever RAISE a flag, never clear one, and a clinician cannot understate a measured
     * finding by leaving a checkbox unticked. Facts derived: fever (measured triage
     * temperature ≥ {@value #FEVER_THRESHOLD_C} °C), haemorrhagic symptoms
     * (coughing/vomiting blood — the VHF/TB bleeding signal), and diarrhoea (the
     * paediatric diarrhoea/vomiting-with-dehydration composite). Each derivation is
     * written into the notes so the record shows WHY a flag the screener didn't tick
     * is set. Null-safe on a missing triage record (door-time screening).
     */
    private void applyTriageDerivedFacts(InfectionScreeningRequest request, TriageRecord triage) {
        if (request == null || triage == null) return;
        List<String> derived = new ArrayList<>();

        Double temp = triage.getVitalSigns() != null ? triage.getVitalSigns().getTemperature() : null;
        if (!request.isHasFever() && temp != null && temp >= FEVER_THRESHOLD_C) {
            request.setHasFever(true);
            derived.add(String.format("fever (measured %.1f °C at triage)", temp));
        }
        if (!request.isHasBleedingSymptoms() && triage.isVuCoughingVomitingBlood()) {
            request.setHasBleedingSymptoms(true);
            derived.add("bleeding symptoms (coughing/vomiting blood at triage)");
        }
        if (!request.isHasDiarrhea() && triage.isUrgPedsDiarrheaVomitingDehydration()) {
            request.setHasDiarrhea(true);
            derived.add("diarrhoea (paediatric diarrhoea/vomiting with dehydration at triage)");
        }

        if (!derived.isEmpty()) {
            String note = "Derived from triage: " + String.join("; ", derived) + ".";
            request.setNotes(request.getNotes() == null || request.getNotes().isBlank()
                    ? note : request.getNotes() + "\n" + note);
        }
    }

    /**
     * Should the auto path file a NEW screening given what is already open? Always, when
     * nothing is open; otherwise only when the dry-run STRICTLY escalates — a stricter
     * precaution, or a more urgent notifiable disease than any already recorded. A
     * re-triage of an already-isolated patient at the same level files nothing (no
     * screening churn, no duplicate RBC paging).
     */
    private boolean escalatesBeyondPrior(InfectionScreeningResult result, List<InfectionScreening> priorOpen) {
        if (priorOpen.isEmpty()) return true;
        IsolationType priorStrictest = null;
        NotifiableDisease priorMostUrgent = null;
        for (InfectionScreening p : priorOpen) {
            priorStrictest = InfectionScreeningEngine.strictest(priorStrictest, p.getIsolationType());
            priorMostUrgent = InfectionScreeningEngine.moreUrgent(priorMostUrgent, p.getNotifiableDisease());
        }
        boolean stricterType = result.isolationType() != null
                && InfectionScreeningEngine.strictest(result.isolationType(), priorStrictest) == result.isolationType()
                && result.isolationType() != priorStrictest;
        boolean moreUrgentDisease = result.notifiableDisease() != null
                && (priorMostUrgent == null
                    || (InfectionScreeningEngine.moreUrgent(result.notifiableDisease(), priorMostUrgent) == result.notifiableDisease()
                        && result.notifiableDisease() != priorMostUrgent));
        return stricterType || moreUrgentDisease;
    }

    /** Free-text markers that suggest an infectious presentation worth a human screening. */
    private static final List<String> COMPLAINT_KEYWORDS = List.of(
            "fever", "febrile", "diarr", "rash", "cough", "night sweat",
            "coughing blood", "vomiting blood", "hemoptysis", "haemoptysis");

    /**
     * Infection suspicion that does NOT amount to a precaution on its own: a measured
     * fever, or an infectious-sounding presenting complaint / additional triage sign.
     * Returns a human-readable reason for the prompt, or null when there is none.
     */
    private String unexplainedInfectionSuspicion(Visit visit, TriageRecord triage, InfectionScreeningRequest derived) {
        if (derived.isHasFever()) {
            Double temp = triage.getVitalSigns() != null ? triage.getVitalSigns().getTemperature() : null;
            return "Measured fever" + (temp != null ? String.format(" of %.1f °C", temp) : "")
                    + " at triage with no infection screening on file";
        }
        String texts = String.join(" ",
                visit.getChiefComplaint() != null ? visit.getChiefComplaint() : "",
                triage.getAdditionalEmergencySigns() != null ? triage.getAdditionalEmergencySigns() : "",
                triage.getAdditionalVeryUrgentSigns() != null ? triage.getAdditionalVeryUrgentSigns() : "",
                triage.getAdditionalUrgentSigns() != null ? triage.getAdditionalUrgentSigns() : "")
                .toLowerCase();
        for (String kw : COMPLAINT_KEYWORDS) {
            if (texts.contains(kw)) {
                return "Presenting complaint suggests possible infection (\"" + kw
                        + "\") with no infection screening on file";
            }
        }
        return null;
    }

    /**
     * Initialise every lazy association {@link InfectionScreeningMapper} reads (visit + its
     * patient / bed / zone) so the controller can map the screening AFTER this transaction's
     * session closes without a LazyInitializationException. The mutation paths (assignIsolationRoom
     * / endIsolation / notifyPublicHealth) load via {@code findByIdAndIsActiveTrue} and only touch
     * {@code visit.hospital} before returning, so the mapper's {@code visit.getPatient()} /
     * {@code getCurrentBed()} 500'd every room-assign / de-isolation / RBC-notification (the write
     * committed, but the response failed). Null-safe on missing associations. (The READ paths use
     * JOIN FETCH queries and never reach here.)
     */
    private InfectionScreening hydrateForResponse(InfectionScreening screening) {
        if (screening != null && screening.getVisit() != null) {
            Visit v = screening.getVisit();
            org.hibernate.Hibernate.initialize(v);
            org.hibernate.Hibernate.initialize(v.getCurrentBed());
            org.hibernate.Hibernate.initialize(v.getHospital());
            org.hibernate.Hibernate.initialize(v.getPatient());
        }
        return screening;
    }

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
