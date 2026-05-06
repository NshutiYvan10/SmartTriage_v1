package com.smartTriage.smartTriage_server.module.triage.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.alert.service.AlertEscalationService;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignEvent;
import com.smartTriage.smartTriage_server.module.clinicalsigns.service.ClinicalSignDefinitions;
import com.smartTriage.smartTriage_server.module.triage.dto.PerformTriageRequest;
import com.smartTriage.smartTriage_server.module.triage.dto.TriageRecordResponse;
import com.smartTriage.smartTriage_server.module.triage.engine.RwandaTriageDecisionEngine;
import com.smartTriage.smartTriage_server.module.triage.engine.RwandaTriageDecisionEngine.TriageDecisionResult;
import com.smartTriage.smartTriage_server.module.triage.engine.RwandaPediatricTriageDecisionEngine;
import com.smartTriage.smartTriage_server.module.triage.engine.RwandaPediatricTriageDecisionEngine.PediatricTriageDecisionResult;
import com.smartTriage.smartTriage_server.module.triage.engine.PediatricTewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.engine.TewsCalculator;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.mapper.TriageRecordMapper;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Triage Engine Service — implements the Rwanda National Standard
 * Triage Protocols for both Adult (Over 12) and Child (3-12 years).
 *
 * Workflow:
 * 1. Resolve visit, user, and vital signs
 * 2. Determine form type (adult vs. child) from visit.isPediatric()
 * 3. Calculate TEWS score (adult or pediatric engine — different thresholds)
 * 4. Run the appropriate Rwanda triage decision flowchart:
 * - Adult: RwandaTriageDecisionEngine
 * - Child: RwandaPediatricTriageDecisionEngine (child-specific emergency signs)
 * 5. Record the full triage assessment (every checkbox from both forms)
 * 6. Update visit status and triage fields
 * 7. Generate escalation alerts on re-triage if category worsens
 *
 * This is the most critical service in the system.
 * Clinical accuracy here directly impacts patient survival.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TriageService {

    private final TriageRecordRepository triageRecordRepository;
    private final VisitService visitService;
    private final VisitRepository visitRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final UserRepository userRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final TewsCalculator tewsCalculator;
    private final PediatricTewsCalculator pediatricTewsCalculator;
    private final RwandaTriageDecisionEngine decisionEngine;
    private final RwandaPediatricTriageDecisionEngine pediatricDecisionEngine;
    private final RealTimeEventPublisher eventPublisher;
    private final AlertEscalationService alertEscalationService;
    /** Bootstraps the clinical-signs timeline from each new triage record. */
    private final com.smartTriage.smartTriage_server.module.clinicalsigns.service.ClinicalSignService clinicalSignService;

    /**
     * Perform initial triage or manual re-triage on a visit.
     * Routes to the correct form engine based on patient age:
     * - Adult (Over 12): RwandaTriageDecisionEngine + TewsCalculator
     * - Child (3-12): RwandaPediatricTriageDecisionEngine + PediatricTewsCalculator
     */
    @Transactional
    public TriageRecordResponse performTriage(PerformTriageRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());
        User currentUser = resolveCurrentUser();
        boolean isPediatric = visit.isPediatric();

        // --- Resolve vital signs ---
        VitalSigns vitals = null;
        if (request.getVitalSignsId() != null) {
            vitals = vitalSignsRepository.findByIdAndIsActiveTrue(request.getVitalSignsId())
                    .orElseThrow(() -> new ResourceNotFoundException("VitalSigns", "id", request.getVitalSignsId()));
        } else {
            vitals = vitalSignsRepository
                    .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visit.getId())
                    .orElse(null);
        }

        // If no VitalSigns exist in DB, build a transient one from the triage form
        // values
        // so that TEWS calculation can still use the nurse-entered vitals.
        if (vitals == null && hasFormVitals(request)) {
            log.info("No DB VitalSigns for visit {} — using triage form vitals for TEWS", visit.getVisitNumber());
            vitals = VitalSigns.builder()
                    .visit(visit)
                    .recordedAt(Instant.now())
                    .respiratoryRate(request.getRespiratoryRate())
                    .heartRate(request.getHeartRate())
                    .systolicBp(request.getSystolicBP())
                    .temperature(request.getTemperature())
                    .spo2(request.getSpo2())
                    .painScore(request.getPainScore())
                    .source(com.smartTriage.smartTriage_server.common.enums.VitalSource.MANUAL_ENTRY)
                    .build();
            // Persist so future lookups find it
            vitals = vitalSignsRepository.save(vitals);
        } else if (vitals != null) {
            // Fill in any missing vital fields from the form if the DB record has gaps
            if (vitals.getRespiratoryRate() == null && request.getRespiratoryRate() != null)
                vitals.setRespiratoryRate(request.getRespiratoryRate());
            if (vitals.getHeartRate() == null && request.getHeartRate() != null)
                vitals.setHeartRate(request.getHeartRate());
            if (vitals.getSystolicBp() == null && request.getSystolicBP() != null)
                vitals.setSystolicBp(request.getSystolicBP());
            if (vitals.getTemperature() == null && request.getTemperature() != null)
                vitals.setTemperature(request.getTemperature());
            if (vitals.getSpo2() == null && request.getSpo2() != null)
                vitals.setSpo2(request.getSpo2());
            if (vitals.getPainScore() == null && request.getPainScore() != null)
                vitals.setPainScore(request.getPainScore());
        }

        // --- STEP 1: Calculate TEWS (uses correct engine based on patient type) ---
        int tewsScore;
        if (vitals != null) {
            tewsScore = calculateTews(vitals, request, visit);
        } else {
            tewsScore = 0;
            log.warn("No vitals available for TEWS calculation on visit {} — defaulting to 0", visit.getVisitNumber());
        }

        // --- STEP 2: Run the correct decision engine ---
        // Prefer request-level spo2/painScore (nurse just entered them), fall back to
        // DB vitals
        Integer spo2 = request.getSpo2() != null ? request.getSpo2()
                : (vitals != null ? vitals.getSpo2() : null);
        Integer painScore = request.getPainScore() != null ? request.getPainScore()
                : (vitals != null ? vitals.getPainScore() : null);

        TriageCategory category;
        String decisionPath;

        if (isPediatric) {
            // CHILD form (3-12 years) — uses child-specific emergency signs
            PediatricTriageDecisionResult pedDecision = pediatricDecisionEngine.decide(
                    tewsScore, spo2, painScore, request);
            category = pedDecision.category();
            decisionPath = pedDecision.decisionPath();
        } else {
            // ADULT form (Over 12 years)
            TriageDecisionResult adultDecision = decisionEngine.decide(
                    tewsScore, spo2, painScore, request);
            category = adultDecision.category();
            decisionPath = adultDecision.decisionPath();
        }

        // --- STEP 3: Determine re-triage ---
        boolean isRetriage = visit.getCurrentTriageCategory() != null;
        TriageCategory previousCategory = visit.getCurrentTriageCategory();

        // --- STEP 4: Build triage record with ALL form fields ---
        TriageRecord record = TriageRecord.builder()
                .visit(visit)
                .triagedBy(currentUser)
                .vitalSigns(vitals)
                .triageTime(Instant.now())

                // Emergency Signs (shared between adult and child)
                .hasAirwayCompromise(request.isHasAirwayCompromise())
                .hasBreathingDistress(request.isHasBreathingDistress())
                .hasSevereRespiratoryDistress(request.isHasSevereRespiratoryDistress())
                .hasCardiacArrest(request.isHasCardiacArrest())
                .hasUncontrolledHaemorrhage(request.isHasUncontrolledHaemorrhage())
                .hasStabGunWoundNeckChest(request.isHasStabGunWoundNeckChest())
                .hasConvulsions(request.isHasConvulsions())
                .convulsionGlucose(request.getConvulsionGlucose())
                .hasComa(request.isHasComa())
                .comaGlucose(request.getComaGlucose())
                .hasHypoglycaemia(request.isHasHypoglycaemia())
                .hasPurpuricRash(request.isHasPurpuricRash())
                .hasBurnFaceInhalation(request.isHasBurnFaceInhalation())

                // Child-specific emergency signs
                .isChildForm(isPediatric)
                .childCentralCyanosis(request.isChildCentralCyanosis())
                .childPulseLowOrAbsent(request.isChildPulseLowOrAbsent())
                .childColdHandsComposite(request.isChildColdHandsComposite())
                .childColdHandsLethargic(request.isChildColdHandsLethargic())
                .childColdHandsPulseWeakFast(request.isChildColdHandsPulseWeakFast())
                .childColdHandsCapRefill(request.isChildColdHandsCapRefill())
                .childSevereDehydration(request.isChildSevereDehydration())
                .childDehydrationSkinPinch(request.isChildDehydrationSkinPinch())
                .childDehydrationLethargy(request.isChildDehydrationLethargy())
                .childDehydrationSunkenEyes(request.isChildDehydrationSunkenEyes())
                .childWeightKg(request.getChildWeightKg())
                .childHeightCm(request.getChildHeightCm())

                // Additional Vitals (not TEWS-scored)
                .spo2(request.getSpo2())
                .diastolicBp(request.getDiastolicBp())
                .bloodGlucose(request.getBloodGlucose())
                .painScore(request.getPainScore())
                .weightKg(request.getWeightKg())
                .heightCm(request.getHeightCm())

                // TEWS components
                .mobility(request.getMobility())
                .avpu(request.getAvpu())
                .traumaStatus(request.getTraumaStatus())

                // Very Urgent Signs — Medical
                .vuFocalNeurologicDeficit(request.isVuFocalNeurologicDeficit())
                .vuAlteredMentalStatus(request.isVuAlteredMentalStatus())
                .vuNeurologicalGlucose(request.getVuNeurologicalGlucose())
                .vuChestPain(request.isVuChestPain())
                .vuPoisoningOverdose(request.isVuPoisoningOverdose())
                .vuPregnantAbdominalPain(request.isVuPregnantAbdominalPain())
                .vuCoughingVomitingBlood(request.isVuCoughingVomitingBlood())
                .vuDiabeticHighGlucose(request.isVuDiabeticHighGlucose())
                .vuDiabeticGlucose(request.getVuDiabeticGlucose())
                .vuAggression(request.isVuAggression())
                .vuShortnessOfBreath(request.isVuShortnessOfBreath())

                // Very Urgent Signs — Trauma
                .vuBurnOver20Percent(request.isVuBurnOver20Percent())
                .vuOpenFracture(request.isVuOpenFracture())
                .vuThreatenedLimb(request.isVuThreatenedLimb())
                .vuEyeInjury(request.isVuEyeInjury())
                .vuLargeJointDislocation(request.isVuLargeJointDislocation())
                .vuSevereMechanismOfInjury(request.isVuSevereMechanismOfInjury())
                .vuVerySeverePain(request.isVuVerySeverePain())
                .vuPregnantAbdominalTrauma(request.isVuPregnantAbdominalTrauma())

                // Urgent Signs
                .urgUnableToDrinkVomits(request.isUrgUnableToDrinkVomits())
                .urgAbdominalPain(request.isUrgAbdominalPain())
                .urgVeryPale(request.isUrgVeryPale())
                .urgPregnantVaginalBleeding(request.isUrgPregnantVaginalBleeding())
                .urgDiabeticVeryHighGlucose(request.isUrgDiabeticVeryHighGlucose())
                .urgDiabeticGlucose(request.getUrgDiabeticGlucose())
                .urgFingerToeDislocation(request.isUrgFingerToeDislocation())
                .urgClosedFracture(request.isUrgClosedFracture())
                .urgBurnWithoutUrgentSigns(request.isUrgBurnWithoutUrgentSigns())
                .urgPregnantTraumaNonAbdominal(request.isUrgPregnantTraumaNonAbdominal())
                .urgModeratePain(request.isUrgModeratePain())
                .urgLacerationAbscess(request.isUrgLacerationAbscess())
                .urgForeignBodyAspiration(request.isUrgForeignBodyAspiration())

                // Computed
                .tewsScore(tewsScore)
                .triageCategory(category)
                .decisionPath(decisionPath)

                // Metadata
                .isRetriage(isRetriage)
                .isSystemTriggered(false)
                .previousCategory(previousCategory)
                .presentingComplaints(request.getPresentingComplaints())
                .clinicalNotes(request.getClinicalNotes())

                // Special Considerations
                .specialAcuteTrauma(request.isSpecialAcuteTrauma())
                .specialSeizureHistory(request.isSpecialSeizureHistory())
                .specialAssaultAbuse(request.isSpecialAssaultAbuse())
                .specialSuicideAttempt(request.isSpecialSuicideAttempt())

                // Form Footer — Nurse & Doctor Notification
                .triageNurseName(request.getTriageNurseName())
                .notifiedDoctorName(request.getNotifiedDoctorName())
                .doctorNotifiedAt(parseInstant(request.getDoctorNotifiedAt()))
                .attendingDoctorName(request.getAttendingDoctorName())
                .doctorAttendedAt(parseInstant(request.getDoctorAttendedAt()))

                .build();

        record = triageRecordRepository.save(record);

        // --- STEP 5: Update Visit ---
        visit.setCurrentTriageCategory(category);
        visit.setCurrentTewsScore(tewsScore);
        visit.setTriageTime(record.getTriageTime());
        visit.setStatus(VisitStatus.TRIAGED);
        if (isRetriage) {
            visit.setRetriageCount(visit.getRetriageCount() + 1);
        }
        visitRepository.save(visit);

        // --- STEP 6: Generate zone-routed alerts ---
        if (isRetriage && isEscalation(previousCategory, category)) {
            generateEscalationAlert(visit, previousCategory, category, tewsScore);
        }

        // Zone-aware doctor notification for RED/ORANGE/YELLOW — replaces manual doctor
        // name entry.
        // Routes to the doctor assigned to the target ED zone for this shift.
        if (category == TriageCategory.RED || category == TriageCategory.ORANGE || category == TriageCategory.YELLOW) {
            try {
                alertEscalationService.createZoneRoutedAlert(visit, category, tewsScore, decisionPath);
            } catch (Exception e) {
                log.warn("Failed to create zone-routed alert for visit {}: {}", visit.getVisitNumber(), e.getMessage());
                // Fallback: still generate the old-style alert
                generateHighAcuityAlert(visit, category, tewsScore, decisionPath);
            }
        }

        // --- STEP 7: Push real-time WebSocket triage notification ---
        try {
            eventPublisher.publishTriageChange(visit.getId(), Map.of(
                    "visitId", visit.getId().toString(),
                    "visitNumber", visit.getVisitNumber(),
                    "patientName", visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName(),
                    "triageCategory", category.name(),
                    "tewsScore", tewsScore,
                    "decisionPath", decisionPath,
                    "isRetriage", isRetriage,
                    "nurseName", request.getTriageNurseName() != null ? request.getTriageNurseName() : ""));
        } catch (Exception e) {
            log.warn("Failed to publish WebSocket notification for triage: {}", e.getMessage());
        }

        log.info("Triage completed: Visit {} → {} (TEWS: {}) | Decision: {} | Retriage: {} | Form: {}",
                visit.getVisitNumber(), category, tewsScore, decisionPath, isRetriage,
                isPediatric ? "Child (3-12)" : "Adult (Over 12)");

        // Bootstrap the clinical-signs timeline. Each positive triage flag
        // (emergency sign, mSAT VU/URG discriminator, special consideration)
        // becomes a PRESENT, isBaseline=true event so the doctor's Clinical
        // Signs tab opens populated rather than empty. Failure here is logged
        // inside the service and never propagated — triage submission must
        // not fail because of timeline bookkeeping.
        clinicalSignService.recordBaselineFromTriage(record);

        return TriageRecordMapper.toResponse(record);
    }

    /**
     * Get triage history for a visit.
     */
    public Page<TriageRecordResponse> getTriageHistory(UUID visitId, Pageable pageable) {
        return triageRecordRepository.findByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId, pageable)
                .map(TriageRecordMapper::toResponse);
    }

    /**
     * Get the latest triage record for a visit.
     */
    public TriageRecordResponse getLatestTriage(UUID visitId) {
        TriageRecord record = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No triage record found for visit: " + visitId));
        return TriageRecordMapper.toResponse(record);
    }

    // --- Private helper methods ---

    private int calculateTews(VitalSigns vitals, PerformTriageRequest request, Visit visit) {
        if (visit.isPediatric()) {
            return pediatricTewsCalculator.calculatePediatricTewsScore(
                    vitals, request.getMobility(), request.getAvpu(), request.getTraumaStatus());
        }
        return tewsCalculator.calculateTewsScore(
                vitals, request.getMobility(), request.getAvpu(), request.getTraumaStatus());
    }

    private boolean isEscalation(TriageCategory previous, TriageCategory current) {
        if (previous == null || current == null)
            return false;
        return current.getSeverity() > previous.getSeverity();
    }

    /**
     * Round 3 — system-triggered re-triage.
     *
     * <p>Called by {@link com.smartTriage.smartTriage_server.module.clinicalsigns.service.ClinicalSignService}
     * when the {@link RetriageEvaluator} returns an {@code AutoBump} for a
     * freshly-recorded clinical-sign event. Builds and persists a new
     * TriageRecord pinned to the target category, with the audit link to
     * the trigger event, increments the visit's retriage count, updates
     * its current category, and fires the same CRITICAL/HIGH escalation
     * alert the manual path would have produced.
     *
     * <p>Idempotency: skip when the visit already has a triage record at
     * or above the target category in the last 60 seconds. Stops a batch
     * of three Emergency Signs from creating three duplicate RED records.
     *
     * <p>This method does NOT run the Rwanda decision engine. The target
     * category is supplied by the caller's deterministic decision (e.g.
     * EMERGENCY → RED). TEWS is preserved from the latest manual triage
     * because the score depends on vitals, which we don't have new ones
     * for; the doctor's own re-triage will recompute it when they arrive.
     */
    @Transactional
    public TriageRecordResponse systemTriggeredRetriage(
            Visit visit, ClinicalSignEvent triggerEvent,
            TriageCategory targetCategory, String reason) {
        // Idempotency window — same constant as the Rwandan triage SOP's
        // "do not re-triage twice in less than a minute" guidance.
        java.time.Instant since = java.time.Instant.now().minusSeconds(60);
        java.util.List<TriageCategory> atOrAbove = java.util.Arrays.stream(TriageCategory.values())
                .filter(c -> c.getSeverity() >= targetCategory.getSeverity())
                .toList();
        if (triageRecordRepository.hasRecentTriageAtOrAboveCategory(visit.getId(), since, atOrAbove)) {
            log.info("Skipping system-triggered re-triage on visit {}: already at or above {} within idempotency window",
                    visit.getVisitNumber(), targetCategory);
            // Still return the latest record so the caller has something
            // sensible to surface; the caller's tx is unaffected.
            return getLatestTriage(visit.getId());
        }

        TriageCategory previousCategory = visit.getCurrentTriageCategory();
        int carriedTews = visit.getCurrentTewsScore() == null ? 0 : visit.getCurrentTewsScore();
        String label = ClinicalSignDefinitions.labelOrCode(triggerEvent.getSignCode());
        String decisionPath = String.format(
                "System re-triage: %s → %s. Triggered by %s (%s) at %s by %s.",
                previousCategory == null ? "—" : previousCategory.name(),
                targetCategory.name(),
                label,
                triggerEvent.getStatus(),
                triggerEvent.getRecordedAt(),
                triggerEvent.getRecordedByName() != null ? triggerEvent.getRecordedByName() : "system");

        // Build a minimal TriageRecord — we don't have a fresh form, so
        // the boolean flags carry over false and we lean on
        // decisionPath + previousCategory + triggeringSignEventId for
        // the audit trail. The doctor's manual re-triage will produce
        // a full record with all checkboxes.
        TriageRecord record = TriageRecord.builder()
                .visit(visit)
                .triagedBy(triggerEvent.getRecordedBy())
                .triageNurseName(triggerEvent.getRecordedByName())
                .triageTime(java.time.Instant.now())
                .tewsScore(carriedTews)
                .triageCategory(targetCategory)
                .decisionPath(decisionPath)
                .isRetriage(true)
                .isSystemTriggered(true)
                .previousCategory(previousCategory)
                .triggeringSignEventId(triggerEvent.getId())
                .clinicalNotes(reason)
                .isChildForm(visit.isPediatric())
                .build();
        record = triageRecordRepository.save(record);

        // Update visit
        visit.setCurrentTriageCategory(targetCategory);
        visit.setTriageTime(record.getTriageTime());
        visit.setStatus(VisitStatus.TRIAGED);
        visit.setRetriageCount(visit.getRetriageCount() + 1);
        visitRepository.save(visit);

        // Fire CRITICAL alert mirroring the manual escalation path so the
        // existing zone-routed alert pipeline picks it up. The trigger
        // event id + sign code travel on the alert so the frontend
        // click-through (Round 4a) can land the nurse on a pre-flagged
        // triage form without re-deriving the trigger.
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.RETRIAGE_REQUIRED)
                .severity(targetCategory == TriageCategory.RED ? AlertSeverity.CRITICAL : AlertSeverity.HIGH)
                .title("Auto re-triage: " + targetCategory + " — " + label)
                .message(String.format(
                        "Patient %s %s (Visit %s) auto-escalated to %s after %s recorded as %s. Reason: %s",
                        visit.getPatient().getFirstName(),
                        visit.getPatient().getLastName(),
                        visit.getVisitNumber(),
                        targetCategory,
                        label,
                        triggerEvent.getStatus(),
                        reason))
                .triggeringSignEventId(triggerEvent.getId())
                .triggeringSignCode(triggerEvent.getSignCode())
                .autoGenerated(true)
                .build();
        clinicalAlertRepository.save(alert);
        log.warn("AUTO RE-TRIAGE: Visit {} → {} ({})", visit.getVisitNumber(), targetCategory, label);

        return TriageRecordMapper.toResponse(record, triggerEvent);
    }

    /**
     * Round 3 — create a {@code RETRIAGE_REQUIRED} alert when the
     * {@link RetriageEvaluator} returns a Suggest decision (mSAT VU/URG
     * worsenings where category recomputation needs nurse judgement).
     *
     * <p>Idempotency: skip when an unacknowledged RETRIAGE_REQUIRED alert
     * already exists for this visit + sign code. Re-recording the same
     * worsening sign during the same shift shouldn't spam the queue.
     */
    @Transactional
    public void createRetriageSuggestionAlert(
            Visit visit, ClinicalSignEvent triggerEvent,
            AlertSeverity severity, String message) {
        String label = ClinicalSignDefinitions.labelOrCode(triggerEvent.getSignCode());
        boolean alreadyOpen = clinicalAlertRepository
                .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                        visit.getId(), AlertType.RETRIAGE_REQUIRED);
        if (alreadyOpen) {
            log.info("Skipping retriage-suggestion alert on visit {}: an unacked one is already open",
                    visit.getVisitNumber());
            return;
        }
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.RETRIAGE_REQUIRED)
                .severity(severity)
                .title("Re-triage suggested: " + label)
                .message(message)
                .triggeringSignEventId(triggerEvent.getId())
                .triggeringSignCode(triggerEvent.getSignCode())
                .autoGenerated(true)
                .build();
        clinicalAlertRepository.save(alert);
        log.info("RETRIAGE SUGGESTION: Visit {} ({})", visit.getVisitNumber(), label);
    }

    private void generateEscalationAlert(Visit visit, TriageCategory from, TriageCategory to, int tewsScore) {
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.TEWS_ESCALATION)
                .severity(to == TriageCategory.RED ? AlertSeverity.CRITICAL : AlertSeverity.HIGH)
                .title("Triage Escalation: " + from + " → " + to)
                .message(String.format(
                        "Patient %s %s (Visit: %s) has been escalated from %s to %s. TEWS Score: %d. Immediate clinical review required.",
                        visit.getPatient().getFirstName(),
                        visit.getPatient().getLastName(),
                        visit.getVisitNumber(),
                        from.getDescription(),
                        to.getDescription(),
                        tewsScore))
                .autoGenerated(true)
                .build();

        clinicalAlertRepository.save(alert);
        log.warn("ESCALATION ALERT: Visit {} escalated {} → {} (TEWS: {})",
                visit.getVisitNumber(), from, to, tewsScore);
    }

    private void generateHighAcuityAlert(Visit visit, TriageCategory category,
            int tewsScore, String decisionPath) {
        AlertSeverity severity = category == TriageCategory.RED
                ? AlertSeverity.CRITICAL
                : AlertSeverity.HIGH;

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(category == TriageCategory.RED
                        ? AlertType.TEWS_CRITICAL
                        : AlertType.TEWS_ESCALATION)
                .severity(severity)
                .title(category + " Triage: " + visit.getVisitNumber())
                .message(String.format(
                        "Patient %s %s triaged as %s (%s). TEWS: %d. Decision: %s. " +
                                "Doctor notification required per national triage protocol.",
                        visit.getPatient().getFirstName(),
                        visit.getPatient().getLastName(),
                        category.name(),
                        category.getDescription(),
                        tewsScore,
                        decisionPath))
                .autoGenerated(true)
                .build();

        clinicalAlertRepository.save(alert);
        log.warn("{} TRIAGE ALERT: Visit {} — {} (TEWS: {})",
                category, visit.getVisitNumber(), decisionPath, tewsScore);
    }

    /** Parse an ISO-8601 timestamp string to Instant, returning null on failure */
    private Instant parseInstant(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank())
            return null;
        try {
            return Instant.parse(isoTimestamp);
        } catch (Exception e) {
            log.warn("Failed to parse timestamp '{}': {}", isoTimestamp, e.getMessage());
            return null;
        }
    }

    private User resolveCurrentUser() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User) {
                return (User) principal;
            }
        } catch (Exception e) {
            log.debug("Could not resolve current user from security context");
        }
        return null;
    }

    /**
     * Check if the triage request contains any manually-entered vital sign values
     */
    private boolean hasFormVitals(PerformTriageRequest request) {
        return request.getRespiratoryRate() != null
                || request.getHeartRate() != null
                || request.getSystolicBP() != null
                || request.getTemperature() != null;
    }
}
