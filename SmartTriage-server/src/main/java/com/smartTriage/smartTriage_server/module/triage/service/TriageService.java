package com.smartTriage.smartTriage_server.module.triage.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.alert.service.AlertEscalationService;
import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
import com.smartTriage.smartTriage_server.module.bed.service.BedService;
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
    /** B10 — persists a phone captured/edited on the triage form back onto
     *  the patient so the nurse's correction isn't dropped. */
    private final com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository patientRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final UserRepository userRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final TewsCalculator tewsCalculator;
    private final PediatricTewsCalculator pediatricTewsCalculator;
    private final RwandaTriageDecisionEngine decisionEngine;
    private final RwandaPediatricTriageDecisionEngine pediatricDecisionEngine;
    private final RealTimeEventPublisher eventPublisher;
    /** V54 — stops triage-monitor sessions on triage submit so they don't leak. */
    private final com.smartTriage.smartTriage_server.module.iot.service.DeviceService deviceService;
    private final AlertEscalationService alertEscalationService;
    /** Bootstraps the clinical-signs timeline from each new triage record. */
    private final com.smartTriage.smartTriage_server.module.clinicalsigns.service.ClinicalSignService clinicalSignService;
    /** Resolves the canonical zone for a visit at triage / re-triage time. */
    private final com.smartTriage.smartTriage_server.module.visit.service.ZoneRoutingService zoneRoutingService;
    /** Phase 2 — initiates pending zone transfers on auto-retriage. */
    private final com.smartTriage.smartTriage_server.module.zonetransfer.service.ZoneTransferService zoneTransferService;
    /** Phase G #2 — surfaces bed suggestion on the post-triage response. */
    private final BedService bedService;

    /**
     * Perform initial triage or manual re-triage on a visit.
     * Routes to the correct form engine based on patient age:
     * - Adult (Over 12): RwandaTriageDecisionEngine + TewsCalculator
     * - Child (3-12): RwandaPediatricTriageDecisionEngine + PediatricTewsCalculator
     */
    @Transactional
    public TriageRecordResponse performTriage(PerformTriageRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());

        // B10 — persist a phone captured/edited on the triage form's "Phone
        // Number" field so the nurse's correction isn't dropped (it previously
        // had no DTO slot). That field round-trips the patient's
        // emergency-contact phone; only write a non-blank value so we never
        // blank out an existing number.
        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
            var triagePatient = visit.getPatient();
            if (triagePatient != null) {
                triagePatient.setEmergencyContactPhone(request.getPhoneNumber().trim());
                patientRepository.save(triagePatient);
            }
        }

        User currentUser = resolveCurrentUser();
        boolean isPediatric = visit.isPediatric();

        // --- Resolve vital signs ---
        // The nurse's form values are the authoritative input for THIS
        // triage assessment and must never be silently overridden by a
        // stale IoT snapshot. Strict priority order:
        //
        //   1. Explicit vitalSignsId reference → use that exact row
        //      (rare; audit-replay or vitals-first workflow).
        //   2. Form carries any vital value → build a fresh VitalSigns
        //      row from the form and persist it. Applies equally to
        //      manually-typed values and values pulled from a monitor
        //      and (possibly) edited in the form. The new row becomes
        //      the latest VitalSigns for the visit and the one bound
        //      to the TriageRecord below.
        //   3. No form vitals → fall back to the latest VitalSigns on
        //      file (e.g. paramedic baseline, prior care). Preserves
        //      the legacy behaviour for vitals-less manual triage.
        //   4. Nothing at all → vitals stays null; the calculator step
        //      below defaults TEWS to 0 and emits a warning log line.
        //
        // Missing form vitals are NOT backfilled from any IoT row —
        // a missing field must never silently inherit a monitor
        // reading the nurse may have rejected. The TEWS calculator
        // already treats null as 0, which is conservative.
        //
        // Regression context: the V54 "Pull from Monitor" feature
        // keeps a triage-zone monitor session open while the nurse
        // fills the form. VitalStreamService periodically writes IoT
        // snapshot VitalSigns rows during that session. The previous
        // resolution logic preferred those snapshots over the form,
        // producing GREEN/2 readings even when the nurse explicitly
        // entered ORANGE-level vitals — the bug this block fixes.
        VitalSigns vitals;
        if (request.getVitalSignsId() != null) {
            vitals = vitalSignsRepository.findByIdAndIsActiveTrue(request.getVitalSignsId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "VitalSigns", "id", request.getVitalSignsId()));
        } else if (hasFormVitals(request)) {
            log.info("Triage on visit {} — building fresh VitalSigns from form values "
                            + "(form is authoritative; any stale IoT snapshot is ignored).",
                    visit.getVisitNumber());
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
            vitals = vitalSignsRepository.save(vitals);
        } else {
            vitals = vitalSignsRepository
                    .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visit.getId())
                    .orElse(null);
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
            // PEDIATRIC — KFH Infant 0-3 or Child 3-12 form. The decision
            // engine + TEWS calculator branch on age in months (<36 → infant)
            // so RR/HR ranges, AVPU options, and form-specific VU/URG
            // discriminators all match the right KFH form.
            int ageInMonths = ageInMonths(visit);
            PediatricTriageDecisionResult pedDecision = pediatricDecisionEngine.decide(
                    ageInMonths, tewsScore, spo2, painScore, request);
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

                // V38 — peds-specific Very Urgent (KFH peds form)
                .vuPedsMoreSleepyThanNormal(request.isVuPedsMoreSleepyThanNormal())
                .vuPedsInconsolableSeverePain(request.isVuPedsInconsolableSeverePain())
                .vuPedsFloppyIrritableRestless(request.isVuPedsFloppyIrritableRestless())
                .vuPedsTinyBabyUnder2Months(request.isVuPedsTinyBabyUnder2Months())
                .vuPedsBurnOver10Percent(request.isVuPedsBurnOver10Percent())

                // V38 — peds-specific Urgent (KFH peds form)
                .urgPedsPittingEdemaFaceOrFeet(request.isUrgPedsPittingEdemaFaceOrFeet())
                .urgPedsSomeRespiratoryDistress(request.isUrgPedsSomeRespiratoryDistress())
                .urgPedsSevereMalnutritionWasting(request.isUrgPedsSevereMalnutritionWasting())
                .urgPedsUnwellWithKnownDiabetes(request.isUrgPedsUnwellWithKnownDiabetes())
                .urgPedsDiarrheaVomitingDehydration(request.isUrgPedsDiarrheaVomitingDehydration())
                .urgPedsDehydrationSunkenEyes(request.isUrgPedsDehydrationSunkenEyes())
                .urgPedsDehydrationDryMouth(request.isUrgPedsDehydrationDryMouth())
                .urgPedsDehydrationDecreasedUrine(request.isUrgPedsDehydrationDecreasedUrine())
                .urgPedsDehydrationSlowSkinPinch(request.isUrgPedsDehydrationSlowSkinPinch())

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
                // V56 — precise user-id links when the nurse picked from
                // the on-duty doctor dropdown. NULL on the locum/free-text
                // path; the name string still carries that info.
                .notifiedDoctor(resolveUserOrNull(request.getNotifiedDoctorUserId()))
                .attendingDoctor(resolveUserOrNull(request.getAttendingDoctorUserId()))

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
        // Phase 1 zone routing — manual triage updates the visit's zone
        // directly. (Phase 2 will introduce a ZoneTransfer state
        // machine that gates inter-zone moves on receiving-doctor
        // acceptance; today the manual triage nurse already saw the
        // patient and chose the category, so the move is final.)
        visit.setCurrentEdZone(zoneRoutingService.routeFor(visit, category));
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

        // Bed assignment — Option A: auto-place in the destination zone
        // when a bed is available, in the same transaction as the triage.
        // Eliminates the modal click that was previously required.
        //
        // Failure modes:
        //   - suggestion engine throws or returns empty (no bed in zone) →
        //     autoPlaced=false; frontend falls back to BedSuggestionModal
        //     and gives the nurse manual placement options.
        //   - placePatient throws (rare — e.g. concurrent placement won
        //     the bed first) → autoPlaced=false; same fallback path.
        //
        // The triage record is still saved either way — the bed is a
        // separate concern, never blocks triage. Patient is triaged;
        // worst case the nurse places them in the next breath.
        Bed suggestedBed = null;
        boolean autoPlaced = false;
        String autoPlacementNote = null;
        try {
            suggestedBed = bedService.suggestBedForVisit(visit.getId()).orElse(null);
        } catch (Exception e) {
            log.warn("Bed suggestion failed for visit {}: {}", visit.getVisitNumber(), e.getMessage());
        }

        if (suggestedBed != null) {
            try {
                com.smartTriage.smartTriage_server.module.bed.dto.PlacePatientRequest placeReq =
                        new com.smartTriage.smartTriage_server.module.bed.dto.PlacePatientRequest();
                placeReq.setVisitId(visit.getId());
                String actorName = currentUser != null
                        ? (currentUser.getFirstName() + " " + currentUser.getLastName()).trim()
                        : "Triage (auto)";
                bedService.placePatient(suggestedBed.getId(), placeReq, actorName);
                autoPlaced = true;
                // Build the note from DEFINITIVE truth — does a monitor
                // session actually exist for this visit now? — not from
                // the suggestedBed.hasMonitor cosmetic flag, which can
                // be stale (set to true on assignDevice but never reset
                // on detach in older code paths). This is what the user
                // sees in the success banner.
                boolean monitorStreaming = bedService.hasActiveSessionForVisit(visit.getId());
                String prefix = "Placed in Bed " + suggestedBed.getCode()
                        + " (" + suggestedBed.getZone() + ")";
                if (monitorStreaming) {
                    autoPlacementNote = prefix + " — monitor streaming.";
                } else if (suggestedBed.isHasMonitor()) {
                    // Bed is flagged as having a monitor but no session
                    // started — most likely the device is OFFLINE. The
                    // simulator's next heartbeat will auto-pair via
                    // DeviceService.autoPairIfMissingSession.
                    autoPlacementNote = prefix + " — monitor offline, will pair on next heartbeat.";
                } else {
                    autoPlacementNote = prefix + " — no monitor paired to this bed.";
                }
                log.info("Auto-placed visit {} in bed {} after triage ({}).",
                        visit.getVisitNumber(), suggestedBed.getCode(),
                        monitorStreaming ? "monitor streaming" : "no live session");
            } catch (Exception e) {
                // Non-fatal — fall through to "suggest, manual confirm"
                // so the triage record is preserved. The frontend modal
                // surfaces this case so the nurse can finish placement.
                autoPlaced = false;
                autoPlacementNote = "Auto-placement failed: " + e.getMessage()
                        + ". Pick a bed manually.";
                log.warn("Auto-place after triage failed for visit {}: {}",
                        visit.getVisitNumber(), e.getMessage());
            }
        } else {
            autoPlacementNote = "No bed available in destination zone — pick one manually.";
        }

        // V54 — close out any triage-zone monitor session started by the
        // "Pull from Monitor" flow. Bedside-monitor sessions are left alone.
        try {
            deviceService.stopTriageMonitorSessionForVisit(
                    visit.getId(),
                    currentUser != null
                            ? (currentUser.getFirstName() + " " + currentUser.getLastName()).trim()
                            : "System");
        } catch (Exception e) {
            log.warn("V54 — non-fatal: triage-monitor session cleanup failed for visit {}: {}",
                    visit.getVisitNumber(), e.getMessage());
        }

        return TriageRecordMapper.toResponse(record, suggestedBed, autoPlaced, autoPlacementNote);
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
            int ageInMonths = ageInMonths(visit);
            return pediatricTewsCalculator.calculatePediatricTewsScore(
                    ageInMonths, vitals, request.getMobility(), request.getAvpu(), request.getTraumaStatus());
        }
        return tewsCalculator.calculateTewsScore(
                vitals, request.getMobility(), request.getAvpu(), request.getTraumaStatus());
    }

    /**
     * Patient age in completed months at triage time. Returns the
     * CHILD-form boundary (36) when the patient's date of birth is
     * unknown — defensive default that errs toward the more
     * conservative (child-form) thresholds rather than treating an
     * unknown-age peds patient as an infant.
     */
    private static int ageInMonths(Visit visit) {
        if (visit == null || visit.getPatient() == null) {
            return PediatricTewsCalculator.INFANT_AGE_BOUNDARY_MONTHS;
        }
        java.time.LocalDate dob = visit.getPatient().getDateOfBirth();
        if (dob == null) return PediatricTewsCalculator.INFANT_AGE_BOUNDARY_MONTHS;
        long months = java.time.temporal.ChronoUnit.MONTHS.between(dob, java.time.LocalDate.now());
        return (int) Math.max(0, months);
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

        // Update visit's category + retriage metadata immediately —
        // the category change is the safety guarantee. Zone change,
        // however, goes through the Phase 2 ZoneTransfer state
        // machine: a PENDING_ACCEPT row is created and the receiving
        // doctor must explicitly take the patient before the
        // visit's current_ed_zone changes.
        EdZone fromZone = visit.getCurrentEdZone();
        EdZone targetZone = zoneRoutingService.routeFor(visit, targetCategory);

        visit.setCurrentTriageCategory(targetCategory);
        visit.setTriageTime(record.getTriageTime());
        visit.setStatus(VisitStatus.TRIAGED);
        visit.setRetriageCount(visit.getRetriageCount() + 1);
        // When the patient hasn't been placed in a zone yet (rare,
        // pre-triage), fall through to direct assignment — there's
        // nothing to "transfer from".
        if (fromZone == null) {
            visit.setCurrentEdZone(targetZone);
        }
        visitRepository.save(visit);

        // Initiate the inter-zone transfer when the zone actually
        // changes. ZoneTransferService idempotently updates an
        // existing pending row to a higher target if one is already
        // open, so a batch of three EMERGENCY signs produces one
        // pending transfer to RESUS, not three.
        if (fromZone != null && fromZone != targetZone) {
            try {
                zoneTransferService.initiate(
                        visit, fromZone, targetZone,
                        "Auto re-triage: " + label + " (" + triggerEvent.getStatus() + ")",
                        triggerEvent.getRecordedBy(),
                        null,
                        triggerEvent.getId());
            } catch (Exception e) {
                log.warn("Failed to initiate zone transfer for visit {}: {}",
                        visit.getVisitNumber(), e.getMessage());
            }
        }

        // Severity is calibrated to the new category's clinical urgency,
        // matching the standard alarm scale. RED → CRITICAL pages the
        // zone doctor at Tier 1; ORANGE → HIGH; YELLOW → MEDIUM.
        // Severity drives the escalation timer in the zone-routing
        // service, so over-tagging YELLOW as CRITICAL trains clinicians
        // to ignore the alarm.
        AlertSeverity severity = switch (targetCategory) {
            case RED -> AlertSeverity.CRITICAL;
            case ORANGE -> AlertSeverity.HIGH;
            case YELLOW -> AlertSeverity.MEDIUM;
            default -> AlertSeverity.MEDIUM;
        };

        // Persist the bare RETRIAGE_REQUIRED alert. The trigger event id
        // + sign code travel on the alert so the frontend click-through
        // (Round 4a) can land the nurse on a pre-flagged triage form
        // without re-deriving the trigger.
        //
        // Audit fix — set targetZone on the alert itself so the alert
        // centre's zone filter (and the WebSocket zone topic) route it
        // to the right team. Previously this alert went out with
        // targetZone=null, which made it appear hospital-wide on every
        // alert list regardless of who was supposed to act on it. The
        // resolved zone is the destination zone implied by the new
        // category (RED→RESUS, ORANGE→ACUTE, …); pediatric overrides
        // are honoured by the existing fromTriageCategory mapping.
        EdZone retriageTargetZone = EdZone.fromTriageCategory(targetCategory);
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.RETRIAGE_REQUIRED)
                .severity(severity)
                .targetZone(retriageTargetZone)
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
        alert = clinicalAlertRepository.save(alert);

        // Round 5 — parity with the IoT auto-retriage path
        // (ContinuousMonitoringEngine.performAutoRetriage). Each step is
        // wrapped in its own try/catch so a transient failure in any
        // downstream surface does not undo the persisted bump. The
        // category change is the safety guarantee; the broadcasts are
        // fan-out optimisations.
        //
        // Step 1 — zone-routed escalation. Pages the zone doctor at
        // Tier 1; the AlertEscalationService scheduler escalates to
        // Tier 2/3 if no acknowledgement arrives. We dedup against any
        // open DOCTOR_NOTIFICATION on this visit to avoid pager spam
        // when several signs land in a tight window.
        try {
            boolean doctorAlertOpen = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            visit.getId(), AlertType.DOCTOR_NOTIFICATION);
            if (!doctorAlertOpen) {
                alertEscalationService.createZoneRoutedAlert(
                        visit, targetCategory, carriedTews,
                        "AUTO RE-TRIAGE: " + label + " (" + triggerEvent.getStatus() + ")");
            }
        } catch (Exception e) {
            log.warn("Failed to create zone-routed alert for auto re-triage on visit {}: {}",
                    visit.getVisitNumber(), e.getMessage());
        }

        // Step 2 — WebSocket fan-out so every open frontend tab
        // (My Patients, Monitoring, zone dashboard, patient list) sees
        // the new category live without a manual refresh. Mirrors the
        // IoT path's hospital + zone broadcasts.
        try {
            UUID hospitalId = visit.getPatient().getHospital().getId();
            ClinicalAlertResponse alertResponse = ClinicalAlertMapper.toResponse(alert);
            eventPublisher.publishHospitalAlert(hospitalId, alertResponse);
            eventPublisher.publishZoneAlert(hospitalId,
                    EdZone.fromTriageCategory(targetCategory), alertResponse);
        } catch (Exception e) {
            log.warn("Failed to broadcast auto-retriage alert for visit {}: {}",
                    visit.getVisitNumber(), e.getMessage());
        }

        // Step 3 — triage-change broadcast. Same payload shape as the
        // manual performTriage path so existing subscribers (e.g. the
        // patient-list re-sort) react identically regardless of how the
        // re-triage was produced.
        try {
            eventPublisher.publishTriageChange(visit.getId(), java.util.Map.of(
                    "visitId", visit.getId().toString(),
                    "visitNumber", visit.getVisitNumber(),
                    "patientName", visit.getPatient().getFirstName() + " "
                            + visit.getPatient().getLastName(),
                    "triageCategory", targetCategory.name(),
                    "tewsScore", carriedTews,
                    "decisionPath", decisionPath,
                    "isRetriage", true,
                    "isSystemTriggered", true,
                    "triggeringSignCode", triggerEvent.getSignCode()));
        } catch (Exception e) {
            log.warn("Failed to publish triage-change WebSocket for auto re-triage on visit {}: {}",
                    visit.getVisitNumber(), e.getMessage());
        }

        // Auditable log line — WARN level so safety-officer grep pulls
        // every system-triggered re-triage by default. Includes visit
        // number, MRN-equivalent (visit number is the human-readable
        // anchor), category transition, trigger sign and recording user.
        log.warn(
                "AUTO RE-TRIAGE: Visit {} {} → {} | trigger={} ({}) | recordedBy={} | severity={}",
                visit.getVisitNumber(),
                previousCategory == null ? "—" : previousCategory.name(),
                targetCategory.name(),
                label,
                triggerEvent.getStatus(),
                triggerEvent.getRecordedByName() != null
                        ? triggerEvent.getRecordedByName() : "system",
                severity);

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
        // Audit fix — set targetZone so this routes to the visit's
        // current zone. The patient hasn't been re-triaged yet (this is
        // the "suggest" path that asks a nurse to make the call), so we
        // use the visit's currentEdZone as the destination, falling
        // back to the category-derived zone if currentEdZone is unset.
        EdZone suggestZone = visit.getCurrentEdZone() != null
                ? visit.getCurrentEdZone()
                : (visit.getCurrentTriageCategory() != null
                        ? EdZone.fromTriageCategory(visit.getCurrentTriageCategory())
                        : null);
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.RETRIAGE_REQUIRED)
                .severity(severity)
                .targetZone(suggestZone)
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
     * V56 — resolve a user-id reference to a User entity for the
     * notified/attending doctor audit link. Returns null on missing id
     * or unknown user — the row stays loose-typed via the free-text
     * name field, same as the locum / free-text fallback path.
     */
    private User resolveUserOrNull(java.util.UUID userId) {
        if (userId == null) return null;
        return userRepository.findByIdAndIsActiveTrue(userId).orElse(null);
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
