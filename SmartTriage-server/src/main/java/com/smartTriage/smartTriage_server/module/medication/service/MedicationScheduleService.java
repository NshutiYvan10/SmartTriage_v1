package com.smartTriage.smartTriage_server.module.medication.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.DoseKind;
import com.smartTriage.smartTriage_server.common.enums.DoseStatus;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.ApproveOrderRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.DelayDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.DiscontinueOrderRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.InfusionEventRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationDoseResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationOrderAuditResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.RecordPrnDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.RefuseDoseRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.ZoneMedicationBoardResponse;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import com.smartTriage.smartTriage_server.module.medication.mapper.MedicationDoseMapper;
import com.smartTriage.smartTriage_server.module.medication.mapper.MedicationMapper;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository;
import com.smartTriage.smartTriage_server.module.medsafety.engine.MedicationSafetyEngine;
import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
import com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MedicationScheduleService — dose-level workflow for typed medication
 * orders (Medication Management module, V67).
 *
 * <p>{@link MedicationService} owns the ORDER lifecycle (prescribe,
 * approve-gating decisions, modify, legacy single-shot flow); this
 * service owns everything that happens dose-by-dose:
 * <ul>
 *   <li>administer / delay / refuse a scheduled or one-time dose
 *       (with dose verification, witness, allergy recheck, safety-
 *       block gate, separation of duties);</li>
 *   <li>PRN administration with minimum-interval, max-per-24h, and
 *       structured vitals-gate enforcement;</li>
 *   <li>continuous-infusion start / rate-change / stop events;</li>
 *   <li>the charge-nurse approval + hold/resume + discontinue
 *       workflows;</li>
 *   <li>the zone medication board and the per-visit audit trail
 *       (structured for the UI, text for the handover report).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MedicationScheduleService {

    private final MedicationAdministrationRepository medicationRepository;
    private final MedicationDoseRepository doseRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final MedicationSafetyCheckRepository medicationSafetyCheckRepository;
    private final MedicationSafetyEngine medicationSafetyEngine;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final MedicationService medicationService;
    /** Pediatric daily-cap weight source: latest triage childWeightKg. */
    private final TriageRecordRepository triageRecordRepository;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Kigali"));

    /** Trailing window for the board's "recently given" lane. */
    private static final Duration RECENT_GIVEN_WINDOW = Duration.ofHours(8);

    // ====================================================================
    // ADMINISTER A DUE DOSE (one-time / scheduled)
    // ====================================================================

    @Transactional
    public MedicationDoseResponse administerDose(UUID doseId, AdministerDoseRequest request) {
        MedicationDose dose = findDoseOrThrow(doseId);
        MedicationAdministration order = dose.getMedication();

        if (dose.getStatus() != DoseStatus.DUE) {
            throw new ClinicalBusinessException(
                    "This dose is " + dose.getStatus() + " — only DUE doses can be administered.");
        }
        requireLiveOrder(order);

        boolean override = Boolean.TRUE.equals(request.getOverride());
        String justification = request.getOverrideJustification();
        if (override) requireJustification(justification);

        User giver = resolveCurrentUser();
        assertSeparationOfDuties(giver, order);
        assertNoUnresolvedSafetyBlock(order);
        String allergyNote = recheckAllergyAtAdministration(order, override, justification);
        assertWitness(order, request.getWitnessName());
        BigDecimal verifiedValue = verifyDose(
                order, request.getDoseValue(), request.getDoseUnit(), override, justification);

        // Cumulative daily-dose cap — the classic accumulation harm
        // (e.g. paracetamol totals) is checked against ALL doses of this
        // drug across the visit in the trailing 24 h, not just this order.
        String capFinding = evaluateDailyDoseCap(order,
                verifiedValue != null ? verifiedValue : order.getDoseValue(),
                request.getDoseUnit() != null ? request.getDoseUnit() : order.getDoseUnit());
        if (capFinding != null && !override) {
            throw new ClinicalBusinessException("Administration blocked: " + capFinding
                    + ". A clinician may override with documented justification.");
        }
        if (capFinding != null) {
            requireJustification(justification);
        }

        Instant now = Instant.now();
        dose.setStatus(DoseStatus.GIVEN);
        dose.setGivenAt(now);
        dose.setGivenBy(giver);
        dose.setGivenByName(request.getAdministeredByName() != null
                ? request.getAdministeredByName() : formatUserName(giver));
        dose.setWitnessName(trimToNull(request.getWitnessName()));
        dose.setDoseValue(verifiedValue != null ? verifiedValue : order.getDoseValue());
        dose.setDoseUnit(request.getDoseUnit() != null ? request.getDoseUnit() : order.getDoseUnit());
        if (override) {
            dose.setOverride(true);
            dose.setOverrideJustification(justification.trim());
            raiseAdministrationOverrideAlert(order, dose, justification);
        }
        if (allergyNote != null) dose.appendStatusReason(allergyNote);
        if (capFinding != null) dose.appendStatusReason("Overridden daily-cap gate: " + capFinding);
        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            dose.appendStatusReason("Note: " + request.getNotes().trim());
        }
        doseRepository.save(dose);

        // Order bookkeeping per type.
        if (order.effectiveType() == PrescriptionType.ONE_TIME) {
            order.setStatus(MedicationStatus.ADMINISTERED);
            order.setAdministeredAt(now);
            order.setAdministeredBy(giver);
            order.setAdministeredByName(dose.getGivenByName());
            medicationRepository.save(order);
        } else if (order.effectiveType() == PrescriptionType.SCHEDULED) {
            rollScheduleForward(order, now);
        }

        log.info("Dose #{} of order {} ({}) GIVEN by {} at {}",
                dose.getSequenceNumber(), order.getId(), order.getDrugName(),
                dose.getGivenByName(), now);
        medicationService.publishOrderEvent(order, "DOSE_GIVEN");
        broadcastOrder(order);
        return MedicationDoseMapper.toResponse(dose);
    }

    // ====================================================================
    // DELAY / REFUSE A DUE DOSE
    // ====================================================================

    @Transactional
    public MedicationDoseResponse delayDose(UUID doseId, DelayDoseRequest request) {
        MedicationDose dose = findDoseOrThrow(doseId);
        if (dose.getStatus() != DoseStatus.DUE) {
            throw new ClinicalBusinessException(
                    "This dose is " + dose.getStatus() + " — only DUE doses can be delayed.");
        }
        requireLiveOrder(dose.getMedication());

        Instant base = dose.getDueAt() != null ? dose.getDueAt() : Instant.now();
        Instant newDue = base.plus(Duration.ofMinutes(request.getDelayMinutes()));
        dose.setDueAt(newDue);
        dose.setDelayCount(dose.getDelayCount() + 1);
        dose.appendStatusReason(String.format("Delayed %d min by %s: %s",
                request.getDelayMinutes(), formatUserName(resolveCurrentUser()),
                request.getReason()));
        // Re-arm the overdue / missed monitoring against the new time.
        dose.setOverdueNotifiedAt(null);
        doseRepository.save(dose);

        log.info("Dose {} of order {} delayed to {} ({})",
                dose.getId(), dose.getMedication().getId(), newDue, request.getReason());
        medicationService.publishOrderEvent(dose.getMedication(), "DOSE_DELAYED");
        return MedicationDoseMapper.toResponse(dose);
    }

    @Transactional
    public MedicationDoseResponse refuseDose(UUID doseId, RefuseDoseRequest request) {
        MedicationDose dose = findDoseOrThrow(doseId);
        MedicationAdministration order = dose.getMedication();
        if (dose.getStatus() != DoseStatus.DUE) {
            throw new ClinicalBusinessException(
                    "This dose is " + dose.getStatus() + " — only DUE doses can be refused.");
        }
        requireLiveOrder(order);

        dose.setStatus(DoseStatus.REFUSED);
        dose.appendStatusReason(String.format("Refused — recorded by %s: %s",
                request.getRecordedByName() != null
                        ? request.getRecordedByName()
                        : formatUserName(resolveCurrentUser()),
                request.getReason()));
        doseRepository.save(dose);

        // The ORDER stays live: the patient may accept the next dose.
        // Roll the schedule forward anchored to the refused dose's slot.
        if (order.effectiveType() == PrescriptionType.SCHEDULED) {
            rollScheduleForward(order,
                    dose.getDueAt() != null ? dose.getDueAt() : Instant.now());
        } else if (order.effectiveType() == PrescriptionType.ONE_TIME) {
            // A refused one-time order is closed out as REFUSED.
            order.setStatus(MedicationStatus.REFUSED);
            String existingNotes = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existingNotes + "REFUSED: " + request.getReason());
            medicationRepository.save(order);
        }

        log.info("Dose {} of order {} REFUSED ({})",
                dose.getId(), order.getId(), request.getReason());
        medicationService.publishOrderEvent(order, "DOSE_REFUSED");
        broadcastOrder(order);
        return MedicationDoseMapper.toResponse(dose);
    }

    // ====================================================================
    // PRN ADMINISTRATION
    // ====================================================================

    @Transactional
    public MedicationDoseResponse recordPrnDose(UUID orderId, RecordPrnDoseRequest request) {
        MedicationAdministration order = findOrderOrThrow(orderId);
        if (order.effectiveType() != PrescriptionType.PRN) {
            throw new ClinicalBusinessException(
                    "Order " + order.getDrugName() + " is not a PRN order.");
        }
        requireLiveOrder(order);

        boolean override = Boolean.TRUE.equals(request.getOverride());
        String justification = request.getOverrideJustification();
        if (override) requireJustification(justification);

        User giver = resolveCurrentUser();
        assertSeparationOfDuties(giver, order);
        assertNoUnresolvedSafetyBlock(order);
        String allergyNote = recheckAllergyAtAdministration(order, override, justification);
        assertWitness(order, request.getWitnessName());
        BigDecimal verifiedValue = verifyDose(
                order, request.getDoseValue(), request.getDoseUnit(), override, justification);

        Instant now = Instant.now();
        List<String> gateFindings = new ArrayList<>();

        // 1. Minimum interval since the last given dose.
        if (order.getPrnMinIntervalHours() != null && order.getPrnMinIntervalHours() > 0) {
            doseRepository
                    .findFirstByMedicationIdAndStatusAndIsActiveTrueOrderByGivenAtDesc(
                            order.getId(), DoseStatus.GIVEN)
                    .ifPresent(last -> {
                        if (last.getGivenAt() == null) return;
                        Instant earliestNext = last.getGivenAt().plus(
                                Duration.ofMinutes(Math.round(order.getPrnMinIntervalHours() * 60)));
                        if (now.isBefore(earliestNext)) {
                            gateFindings.add(String.format(
                                    "Minimum interval not reached — last dose %s, next allowed %s",
                                    TIME_FMT.format(last.getGivenAt()),
                                    TIME_FMT.format(earliestNext)));
                        }
                    });
        }

        // 2. Max doses per trailing 24 h.
        if (order.getPrnMaxDosesPerDay() != null && order.getPrnMaxDosesPerDay() > 0) {
            long given24h = doseRepository.countByMedicationIdAndStatusAndGivenAtAfterAndIsActiveTrue(
                    order.getId(), DoseStatus.GIVEN, now.minus(Duration.ofHours(24)));
            if (given24h >= order.getPrnMaxDosesPerDay()) {
                gateFindings.add(String.format(
                        "24-hour cap reached — %d of %d doses already given",
                        given24h, order.getPrnMaxDosesPerDay()));
            }
        }

        // 3. Structured vitals gate against the LATEST reading. Fail-closed:
        //    no reading on file blocks the dose (override still possible).
        String gateEvaluation = null;
        if (order.getGateParameter() != null && order.getGateComparator() != null
                && order.getGateThreshold() != null) {
            VitalSigns latest = vitalSignsRepository
                    .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(
                            order.getVisit().getId())
                    .orElse(null);
            Double actual = latest != null ? extractGateValue(latest, order) : null;
            if (actual == null) {
                gateEvaluation = String.format("%s gate could not be evaluated — no %s on record",
                        order.getGateParameter().getLabel(), order.getGateParameter().getLabel());
                gateFindings.add(gateEvaluation + " (record vitals first)");
            } else {
                boolean passed = order.getGateComparator()
                        .evaluate(actual, order.getGateThreshold());
                gateEvaluation = String.format("%s %.1f %s %s %.1f %s — %s (recorded %s)",
                        order.getGateParameter().getLabel(), actual,
                        order.getGateParameter().getUnit(),
                        order.getGateComparator().getSymbol(),
                        order.getGateThreshold(), order.getGateParameter().getUnit(),
                        passed ? "passed" : "FAILED",
                        TIME_FMT.format(latest.getRecordedAt()));
                if (!passed) gateFindings.add(gateEvaluation);
            }
        }

        // 4. Cumulative daily-dose cap — same-drug 24h total across the
        //    visit (catches PRN accumulation toward e.g. the paracetamol
        //    daily maximum even when each single dose is in range).
        String dailyCapFinding = evaluateDailyDoseCap(order,
                request.getDoseValue() != null ? request.getDoseValue() : order.getDoseValue(),
                request.getDoseUnit() != null ? request.getDoseUnit() : order.getDoseUnit());
        if (dailyCapFinding != null) gateFindings.add(dailyCapFinding);

        if (!gateFindings.isEmpty() && !override) {
            throw new ClinicalBusinessException(
                    "PRN dose blocked: " + String.join("; ", gateFindings)
                            + ". A clinician may override with documented justification.");
        }

        MedicationDose dose = MedicationDose.builder()
                .medication(order)
                .visit(order.getVisit())
                .kind(DoseKind.PRN_DOSE)
                .status(DoseStatus.GIVEN)
                .sequenceNumber(nextSequence(order))
                .givenAt(now)
                .givenBy(giver)
                .givenByName(request.getAdministeredByName() != null
                        ? request.getAdministeredByName() : formatUserName(giver))
                .witnessName(trimToNull(request.getWitnessName()))
                .doseValue(verifiedValue != null ? verifiedValue : order.getDoseValue())
                .doseUnit(request.getDoseUnit() != null ? request.getDoseUnit() : order.getDoseUnit())
                .prnReason(request.getPrnReason().trim())
                .gateEvaluation(gateEvaluation)
                .build();
        if (!gateFindings.isEmpty()) {
            dose.setOverride(true);
            dose.setOverrideJustification(justification.trim());
            dose.appendStatusReason("Overridden gate(s): " + String.join("; ", gateFindings));
            raiseAdministrationOverrideAlert(order, dose, justification);
        } else if (override) {
            // Override flag sent but nothing actually failed — record the
            // justification anyway; it costs nothing and keeps intent.
            dose.setOverride(true);
            dose.setOverrideJustification(justification.trim());
        }
        if (allergyNote != null) dose.appendStatusReason(allergyNote);
        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            dose.appendStatusReason("Note: " + request.getNotes().trim());
        }
        dose = doseRepository.save(dose);

        log.info("PRN dose of order {} ({}) given by {} — indication: {}",
                order.getId(), order.getDrugName(), dose.getGivenByName(),
                request.getPrnReason());
        medicationService.publishOrderEvent(order, "DOSE_GIVEN");
        return MedicationDoseMapper.toResponse(dose);
    }

    // ====================================================================
    // CONTINUOUS INFUSIONS
    // ====================================================================

    @Transactional
    public MedicationDoseResponse startInfusion(UUID orderId, InfusionEventRequest request) {
        MedicationAdministration order = requireContinuous(orderId);
        requireLiveOrder(order);
        if (isInfusionRunning(order)) {
            throw new ClinicalBusinessException(
                    "An infusion for this order is already running — change its rate or stop it.");
        }
        assertWitness(order, request.getWitnessName());

        Double rate = request.getRateValue() != null ? request.getRateValue() : order.getRateValue();
        String unit = request.getRateUnit() != null ? request.getRateUnit() : order.getRateUnit();

        MedicationDose event = infusionEvent(order, DoseKind.INFUSION_START, rate, unit,
                request.getRecordedByName(), request.getWitnessName(),
                "Infusion started at " + rate + " " + unit
                        + (request.getReason() != null && !request.getReason().isBlank()
                                ? " — " + request.getReason() : ""));
        log.info("Infusion STARTED for order {} ({}) at {} {}",
                order.getId(), order.getDrugName(), rate, unit);
        medicationService.publishOrderEvent(order, "INFUSION_EVENT");
        return MedicationDoseMapper.toResponse(event);
    }

    @Transactional
    public MedicationDoseResponse changeInfusionRate(UUID orderId, InfusionEventRequest request) {
        MedicationAdministration order = requireContinuous(orderId);
        requireLiveOrder(order);
        if (!isInfusionRunning(order)) {
            throw new ClinicalBusinessException(
                    "No running infusion for this order — start it first.");
        }
        if (request.getRateValue() == null) {
            throw new ClinicalBusinessException("A new rate is required for a rate change.");
        }
        String unit = request.getRateUnit() != null ? request.getRateUnit() : order.getRateUnit();
        MedicationDose event = infusionEvent(order, DoseKind.INFUSION_RATE_CHANGE,
                request.getRateValue(), unit,
                request.getRecordedByName(), null,
                "Rate changed to " + request.getRateValue() + " " + unit
                        + (request.getReason() != null && !request.getReason().isBlank()
                                ? " — " + request.getReason() : ""));
        log.info("Infusion rate changed for order {} → {} {}",
                order.getId(), request.getRateValue(), unit);
        medicationService.publishOrderEvent(order, "INFUSION_EVENT");
        return MedicationDoseMapper.toResponse(event);
    }

    @Transactional
    public MedicationDoseResponse stopInfusion(UUID orderId, InfusionEventRequest request) {
        MedicationAdministration order = requireContinuous(orderId);
        if (!isInfusionRunning(order)) {
            throw new ClinicalBusinessException("No running infusion for this order.");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new ClinicalBusinessException("A reason is required to stop an infusion.");
        }
        MedicationDose event = infusionEvent(order, DoseKind.INFUSION_STOP,
                null, null, request.getRecordedByName(), null,
                "Infusion stopped — " + request.getReason());
        log.info("Infusion STOPPED for order {} ({}) — {}",
                order.getId(), order.getDrugName(), request.getReason());
        medicationService.publishOrderEvent(order, "INFUSION_EVENT");
        return MedicationDoseMapper.toResponse(event);
    }

    // ====================================================================
    // APPROVAL GATE / HOLD-RESUME / DISCONTINUE
    // ====================================================================

    @Transactional
    public MedicationResponse approveOrder(UUID orderId, ApproveOrderRequest request) {
        MedicationAdministration order = findOrderOrThrow(orderId);
        if (order.getStatus() != MedicationStatus.PENDING_APPROVAL) {
            throw new ClinicalBusinessException(
                    "Only orders awaiting approval can be approved. Current status: "
                            + order.getStatus());
        }

        User approver = resolveCurrentUser();
        if (approver != null) {
            boolean allowed = approver.getRole() == Role.SUPER_ADMIN
                    || approver.getRole() == Role.DOCTOR
                    || approver.getDesignation() == Designation.CHARGE_NURSE;
            if (!allowed) {
                throw new ClinicalBusinessException(
                        "High-alert orders must be approved by the charge nurse or a doctor.");
            }
            if (order.getPrescribedBy() != null
                    && approver.getId().equals(order.getPrescribedBy().getId())) {
                throw new ClinicalBusinessException(
                        "Separation of duties: the prescriber cannot approve their own "
                                + "high-alert order.");
            }
        }

        order.setStatus(MedicationStatus.PRESCRIBED);
        order.setApprovedBy(approver);
        order.setApprovedByName(request.getApprovedByName() != null
                ? request.getApprovedByName() : formatUserName(approver));
        order.setApprovedAt(Instant.now());
        order.setApprovalNote(trimToNull(request.getNote()));
        medicationRepository.save(order);

        // The order is now administrable — seed its first dose.
        medicationService.createInitialDoseIfNeeded(order, order.getVisit());

        log.info("High-alert order {} ({}) APPROVED by {}",
                order.getId(), order.getDrugName(), order.getApprovedByName());
        medicationService.publishOrderEvent(order, "ORDER_APPROVED");
        broadcastOrder(order);
        return MedicationMapper.toResponse(order);
    }

    /** Un-hold: the order returns to the live set with a fresh due dose. */
    @Transactional
    public MedicationResponse resumeOrder(UUID orderId) {
        MedicationAdministration order = findOrderOrThrow(orderId);
        if (order.getStatus() != MedicationStatus.HELD) {
            throw new ClinicalBusinessException(
                    "Only held orders can be resumed. Current status: " + order.getStatus());
        }
        order.setStatus(MedicationStatus.PRESCRIBED);
        String existingNotes = order.getNotes() != null ? order.getNotes() + " | " : "";
        order.setNotes(existingNotes + "RESUMED by "
                + formatUserName(resolveCurrentUser()) + " at " + TIME_FMT.format(Instant.now()));
        medicationRepository.save(order);

        // A resumed schedule is due NOW — the nurse decided to restart it.
        if (order.effectiveType() == PrescriptionType.SCHEDULED
                || order.effectiveType() == PrescriptionType.ONE_TIME) {
            createDose(order, order.effectiveType() == PrescriptionType.SCHEDULED
                    ? DoseKind.SCHEDULED_DOSE : DoseKind.ONE_TIME_DOSE, Instant.now());
        }

        log.info("Order {} ({}) RESUMED", order.getId(), order.getDrugName());
        medicationService.publishOrderEvent(order, "ORDER_RESUMED");
        broadcastOrder(order);
        return MedicationMapper.toResponse(order);
    }

    @Transactional
    public MedicationResponse discontinueOrder(UUID orderId, DiscontinueOrderRequest request) {
        MedicationAdministration order = findOrderOrThrow(orderId);
        if (order.getStatus() != MedicationStatus.PRESCRIBED
                && order.getStatus() != MedicationStatus.PENDING_APPROVAL
                && order.getStatus() != MedicationStatus.HELD) {
            throw new ClinicalBusinessException(
                    "Only live orders can be discontinued. Current status: " + order.getStatus());
        }

        User actor = resolveCurrentUser();

        // A running infusion is stopped as part of discontinuing.
        if (order.effectiveType() == PrescriptionType.CONTINUOUS && isInfusionRunning(order)) {
            infusionEvent(order, DoseKind.INFUSION_STOP, null, null,
                    request.getDiscontinuedByName(), null,
                    "Infusion stopped — order discontinued: " + request.getReason());
        }
        medicationService.cancelOpenDosesFor(order, "Order discontinued: " + request.getReason());

        order.setStatus(MedicationStatus.DISCONTINUED);
        order.setDiscontinuedAt(Instant.now());
        order.setDiscontinuedBy(actor);
        order.setDiscontinuedByName(request.getDiscontinuedByName() != null
                ? request.getDiscontinuedByName() : formatUserName(actor));
        order.setDiscontinueReason(request.getReason());
        medicationRepository.save(order);

        log.info("Order {} ({}) DISCONTINUED by {} — {}",
                order.getId(), order.getDrugName(), order.getDiscontinuedByName(),
                request.getReason());
        medicationService.publishOrderEvent(order, "ORDER_DISCONTINUED");
        broadcastOrder(order);
        return MedicationMapper.toResponse(order);
    }

    // ====================================================================
    // SCHEDULE ROLL-FORWARD (shared with the dose monitor)
    // ====================================================================

    /**
     * After a SCHEDULED dose resolves (given / refused / missed), create
     * the next DUE dose at {@code anchor + interval} — or COMPLETE the
     * order when its end conditions are met. One open dose per live
     * recurring order, always.
     */
    @Transactional
    public void rollScheduleForward(MedicationAdministration order, Instant anchor) {
        if (order.effectiveType() != PrescriptionType.SCHEDULED) return;
        if (!order.getStatus().isLiveForDosing()) return;
        if (order.getIntervalHours() == null || order.getIntervalHours() <= 0) return;

        long givenCount = doseRepository.countByMedicationIdAndStatusAndIsActiveTrue(
                order.getId(), DoseStatus.GIVEN);
        if (order.getMaxDoses() != null && givenCount >= order.getMaxDoses()) {
            completeOrder(order, "Planned dose count reached (" + givenCount + " given)");
            return;
        }

        Instant nextDue = anchor.plus(Duration.ofMinutes(
                Math.round(order.getIntervalHours() * 60)));
        if (order.getEndAt() != null && nextDue.isAfter(order.getEndAt())) {
            completeOrder(order, "Scheduled duration elapsed");
            return;
        }

        createDose(order, DoseKind.SCHEDULED_DOSE, nextDue);
        log.info("Next dose of order {} ({}) scheduled for {}",
                order.getId(), order.getDrugName(), nextDue);
    }

    /** Planned end reached — order leaves the live set as COMPLETED. */
    @Transactional
    public void completeOrder(MedicationAdministration order, String reason) {
        medicationService.cancelOpenDosesFor(order, "Order completed: " + reason);
        order.setStatus(MedicationStatus.COMPLETED);
        order.setCompletedAt(Instant.now());
        String existingNotes = order.getNotes() != null ? order.getNotes() + " | " : "";
        order.setNotes(existingNotes + "COMPLETED: " + reason);
        medicationRepository.save(order);
        log.info("Order {} ({}) COMPLETED — {}", order.getId(), order.getDrugName(), reason);
        medicationService.publishOrderEvent(order, "ORDER_COMPLETED");
        broadcastOrder(order);
    }

    // ====================================================================
    // ZONE BOARD + AUDIT TRAIL
    // ====================================================================

    public ZoneMedicationBoardResponse getZoneBoard(UUID hospitalId, EdZone zone) {
        List<MedicationDoseResponse> due = doseRepository.findOpenDueForHospital(hospitalId)
                .stream()
                .filter(d -> matchesZone(d.getVisit(), zone))
                .map(MedicationDoseMapper::toResponse)
                .toList();

        List<MedicationDoseResponse> recent = doseRepository
                .findRecentlyGivenForHospital(hospitalId, Instant.now().minus(RECENT_GIVEN_WINDOW))
                .stream()
                .filter(d -> matchesZone(d.getVisit(), zone))
                .map(MedicationDoseMapper::toResponse)
                .toList();

        List<MedicationOrderAuditResponse> prn = medicationRepository
                .findByHospitalAndStatusAndType(hospitalId, MedicationStatus.PRESCRIBED,
                        PrescriptionType.PRN)
                .stream()
                .filter(m -> matchesZone(m.getVisit(), zone))
                .map(this::toAuditEntry)
                .toList();

        List<MedicationOrderAuditResponse> infusions = medicationRepository
                .findByHospitalAndStatusAndType(hospitalId, MedicationStatus.PRESCRIBED,
                        PrescriptionType.CONTINUOUS)
                .stream()
                .filter(m -> matchesZone(m.getVisit(), zone))
                .map(this::toAuditEntry)
                .toList();

        List<MedicationResponse> pendingApproval = medicationRepository
                .findTypedByHospitalAndStatus(hospitalId, MedicationStatus.PENDING_APPROVAL)
                .stream()
                .filter(m -> matchesZone(m.getVisit(), zone))
                .map(MedicationMapper::toResponse)
                .toList();

        return ZoneMedicationBoardResponse.builder()
                .dueDoses(due)
                .recentlyGiven(recent)
                .prnOrders(prn)
                .activeInfusions(infusions)
                .pendingApproval(pendingApproval)
                .build();
    }

    /** Structured per-visit audit: every order with its full dose timeline. */
    public List<MedicationOrderAuditResponse> getVisitAudit(UUID visitId) {
        return medicationRepository.findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visitId)
                .stream()
                .map(this::toAuditEntry)
                .toList();
    }

    /**
     * Text form of the audit trail for the handover report. Built to
     * leave the incoming doctor ZERO ambiguity: every order with its
     * schedule, every dose with actor + time + witness, every miss /
     * hold / refusal / discontinuation with its reason, plus PRN usage
     * and infusion state.
     */
    public String buildMedicationAuditText(Visit visit) {
        List<MedicationAdministration> orders = medicationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visit.getId());
        if (orders.isEmpty()) {
            return "No medications prescribed this visit.";
        }

        StringBuilder sb = new StringBuilder();
        List<MedicationAdministration> active = orders.stream()
                .filter(o -> o.getStatus() == MedicationStatus.PRESCRIBED
                        || o.getStatus() == MedicationStatus.PENDING_APPROVAL
                        || o.getStatus() == MedicationStatus.HELD)
                .toList();
        sb.append("ACTIVE AT HANDOVER: ").append(active.size())
                .append(" of ").append(orders.size()).append(" orders\n\n");

        int idx = 0;
        for (MedicationAdministration order : orders) {
            idx++;
            sb.append(String.format("%d. %s", idx, describeOrderHeader(order)));
            sb.append("\n   Prescribed ").append(TIME_FMT.format(order.getPrescribedAt()))
                    .append(" by ").append(order.getPrescribedByName() != null
                            ? order.getPrescribedByName() : "(unknown)");
            if (order.isApprovalRequired()) {
                sb.append(order.getApprovedAt() != null
                        ? " | HIGH-ALERT — approved by " + order.getApprovedByName()
                                + " " + TIME_FMT.format(order.getApprovedAt())
                        : " | HIGH-ALERT — AWAITING APPROVAL");
            }
            if (order.isEmergencyOverride()) {
                sb.append(" | EMERGENCY OVERRIDE: ").append(order.getEmergencyJustification());
            }
            if (Boolean.TRUE.equals(order.getPrescribedDespiteAllergy())) {
                sb.append(" | ALLERGY OVERRIDE: ").append(order.getAllergyOverrideMatches());
            }
            sb.append("\n   Status: ").append(order.getStatus().getDescription());
            if (order.getStatus() == MedicationStatus.DISCONTINUED) {
                sb.append(" — ").append(order.getDiscontinueReason())
                        .append(" (by ").append(order.getDiscontinuedByName())
                        .append(" ").append(order.getDiscontinuedAt() != null
                                ? TIME_FMT.format(order.getDiscontinuedAt()) : "?")
                        .append(")");
            }
            if (order.getSupersededById() != null) {
                sb.append(" — superseded by a replacement order (modification)");
            }
            if (order.getSupersedesId() != null) {
                sb.append(" — replaces an earlier order (modification)");
            }

            // Schedule / remaining-dose summary.
            PrescriptionType type = order.effectiveType();
            if (type == PrescriptionType.SCHEDULED) {
                long given = doseRepository.countByMedicationIdAndStatusAndIsActiveTrue(
                        order.getId(), DoseStatus.GIVEN);
                sb.append("\n   Schedule: every ").append(order.getIntervalHours()).append(" h");
                if (order.getMaxDoses() != null) {
                    sb.append(" — ").append(given).append(" of ")
                            .append(order.getMaxDoses()).append(" doses given");
                } else {
                    sb.append(" — ").append(given).append(" dose(s) given");
                }
                if (order.getEndAt() != null) {
                    sb.append(", until ").append(TIME_FMT.format(order.getEndAt()));
                }
            } else if (type == PrescriptionType.PRN) {
                long given24h = doseRepository.countByMedicationIdAndStatusAndGivenAtAfterAndIsActiveTrue(
                        order.getId(), DoseStatus.GIVEN, Instant.now().minus(Duration.ofHours(24)));
                sb.append("\n   PRN for ").append(order.getPrnIndication());
                if (order.getPrnMinIntervalHours() != null) {
                    sb.append(" — min ").append(order.getPrnMinIntervalHours()).append(" h between doses");
                }
                if (order.getPrnMaxDosesPerDay() != null) {
                    sb.append(", max ").append(order.getPrnMaxDosesPerDay()).append("/24h");
                }
                sb.append(" (").append(given24h).append(" given in last 24 h)");
                if (order.getGateParameter() != null) {
                    sb.append("\n   Vitals gate: only if ").append(order.getGateParameter().getLabel())
                            .append(" ").append(order.getGateComparator().getSymbol())
                            .append(" ").append(order.getGateThreshold());
                }
            }

            // Dose log.
            List<MedicationDose> doses = doseRepository
                    .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId());
            if (!doses.isEmpty()) {
                sb.append("\n   Dose log:");
                for (MedicationDose d : doses) {
                    sb.append("\n     ").append(describeDoseLine(d));
                }
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    // ====================================================================
    // INTERNAL
    // ====================================================================

    private MedicationOrderAuditResponse toAuditEntry(MedicationAdministration order) {
        List<MedicationDose> doses = doseRepository
                .findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(order.getId());
        MedicationResponse orderDto = MedicationMapper.toResponse(order);
        orderDto.setGivenDoseCount(doses.stream()
                .filter(d -> d.getStatus() == DoseStatus.GIVEN).count());
        orderDto.setNextDueAt(doses.stream()
                .filter(d -> d.getStatus() == DoseStatus.DUE && d.getDueAt() != null)
                .map(MedicationDose::getDueAt)
                .min(Instant::compareTo)
                .orElse(null));
        return MedicationOrderAuditResponse.builder()
                .order(orderDto)
                .doses(doses.stream().map(MedicationDoseMapper::toResponse).toList())
                .build();
    }

    private String describeOrderHeader(MedicationAdministration order) {
        StringBuilder sb = new StringBuilder();
        sb.append(order.getDrugName());
        if (order.getProductDetail() != null) sb.append(" (").append(order.getProductDetail()).append(")");
        if (order.getDoseValue() != null) {
            sb.append(" ").append(order.getDoseValue().stripTrailingZeros().toPlainString())
                    .append(" ").append(order.getDoseUnit() != null ? order.getDoseUnit() : "");
        } else if (order.getDose() != null) {
            sb.append(" ").append(order.getDose());
        }
        if (order.getRoute() != null) sb.append(" ").append(order.getRoute().name());
        sb.append(" — ").append(order.effectiveType().getLabel());
        if (order.effectiveType() == PrescriptionType.CONTINUOUS && order.getRateValue() != null) {
            sb.append(" @ ").append(order.getRateValue()).append(" ").append(order.getRateUnit());
        }
        if (order.getProductType() != null
                && order.getProductType() != com.smartTriage.smartTriage_server.common.enums.MedicationProductType.DRUG) {
            sb.append(" [").append(order.getProductType().getLabel()).append("]");
        }
        if (order.getPriority() != null
                && order.getPriority() != com.smartTriage.smartTriage_server.common.enums.MedicationPriority.ROUTINE) {
            sb.append(" [").append(order.getPriority().name()).append("]");
        }
        return sb.toString();
    }

    private String describeDoseLine(MedicationDose d) {
        StringBuilder sb = new StringBuilder();
        if (d.getSequenceNumber() != null) sb.append("#").append(d.getSequenceNumber()).append(" ");
        sb.append(d.getKind().getLabel()).append(" — ").append(d.getStatus().getLabel());
        if (d.getStatus() == DoseStatus.DUE && d.getDueAt() != null) {
            sb.append(", due ").append(TIME_FMT.format(d.getDueAt()));
            if (d.getDelayCount() > 0) sb.append(" (delayed ×").append(d.getDelayCount()).append(")");
        }
        if (d.getGivenAt() != null) {
            sb.append(" ").append(TIME_FMT.format(d.getGivenAt()))
                    .append(" by ").append(d.getGivenByName() != null ? d.getGivenByName() : "?");
            if (d.getWitnessName() != null) sb.append(" (witness: ").append(d.getWitnessName()).append(")");
            if (d.getDoseValue() != null) {
                sb.append(" — ").append(d.getDoseValue().stripTrailingZeros().toPlainString())
                        .append(" ").append(d.getDoseUnit() != null ? d.getDoseUnit() : "");
            }
            if (d.getRateValue() != null) {
                sb.append(" — ").append(d.getRateValue()).append(" ")
                        .append(d.getRateUnit() != null ? d.getRateUnit() : "");
            }
        }
        if (d.getPrnReason() != null) sb.append(" | indication: ").append(d.getPrnReason());
        if (d.getGateEvaluation() != null) sb.append(" | gate: ").append(d.getGateEvaluation());
        if (d.isOverride()) sb.append(" | OVERRIDE: ").append(d.getOverrideJustification());
        if (d.getStatusReason() != null) sb.append(" | ").append(d.getStatusReason());
        return sb.toString();
    }

    private boolean matchesZone(Visit visit, EdZone zone) {
        if (zone == null) return true;
        return visit != null && visit.getCurrentEdZone() == zone;
    }

    private MedicationDose infusionEvent(MedicationAdministration order, DoseKind kind,
            Double rate, String unit, String recordedByName, String witnessName, String note) {
        User actor = resolveCurrentUser();
        MedicationDose event = MedicationDose.builder()
                .medication(order)
                .visit(order.getVisit())
                .kind(kind)
                .status(DoseStatus.GIVEN)
                .sequenceNumber(nextSequence(order))
                .givenAt(Instant.now())
                .givenBy(actor)
                .givenByName(recordedByName != null ? recordedByName : formatUserName(actor))
                .witnessName(trimToNull(witnessName))
                .rateValue(rate)
                .rateUnit(unit)
                .build();
        event.appendStatusReason(note);
        return doseRepository.save(event);
    }

    /** Running ⇔ the order's latest infusion event exists and isn't a STOP. */
    public boolean isInfusionRunning(MedicationAdministration order) {
        return doseRepository
                .findFirstByMedicationIdAndKindInAndIsActiveTrueOrderByGivenAtDesc(
                        order.getId(),
                        List.of(DoseKind.INFUSION_START, DoseKind.INFUSION_RATE_CHANGE,
                                DoseKind.INFUSION_STOP))
                .map(e -> e.getKind() != DoseKind.INFUSION_STOP)
                .orElse(false);
    }

    private MedicationAdministration requireContinuous(UUID orderId) {
        MedicationAdministration order = findOrderOrThrow(orderId);
        if (order.effectiveType() != PrescriptionType.CONTINUOUS) {
            throw new ClinicalBusinessException(
                    "Order " + order.getDrugName() + " is not a continuous infusion.");
        }
        return order;
    }

    private void requireLiveOrder(MedicationAdministration order) {
        if (order.getStatus() == MedicationStatus.PENDING_APPROVAL) {
            throw new ClinicalBusinessException(
                    "This high-alert order is awaiting charge-nurse approval and cannot be "
                            + "administered yet.");
        }
        if (!order.getStatus().isLiveForDosing()) {
            throw new ClinicalBusinessException(
                    "This order is " + order.getStatus().getDescription()
                            + " — no further administrations are allowed.");
        }
    }

    private void requireJustification(String justification) {
        if (justification == null || justification.trim().length() < 10) {
            throw new ClinicalBusinessException(
                    "An override requires a documented justification (at least 10 characters).");
        }
    }

    /** Same chain-of-custody rule as the legacy administer path. */
    private void assertSeparationOfDuties(User giver, MedicationAdministration order) {
        if (giver != null && order.getPrescribedBy() != null
                && giver.getId().equals(order.getPrescribedBy().getId())) {
            throw new ClinicalBusinessException(
                    "Separation of duties: the clinician who prescribed '"
                            + order.getDrugName()
                            + "' cannot also record its administration. "
                            + "A second clinician must complete this step.");
        }
    }

    /** Same un-overridden CRITICAL safety-block gate as the legacy path. */
    private void assertNoUnresolvedSafetyBlock(MedicationAdministration order) {
        boolean blocked = medicationSafetyCheckRepository
                .findByMedicationIdAndIsActiveTrueOrderByCheckedAtDesc(order.getId())
                .filter(check -> !check.isOverallSafe() && check.getOverriddenBy() == null)
                .isPresent();
        if (blocked) {
            throw new ClinicalBusinessException(
                    "Administration blocked: an unresolved medication safety check exists for '"
                            + order.getDrugName() + "'. A clinician must override the safety "
                            + "check (with a documented reason) before it can be administered.");
        }
    }

    /**
     * Allergy recheck at administration time — the patient's allergy
     * list may have grown SINCE prescribing (triage captured a new
     * anaphylaxis, say). Skipped when the prescriber already
     * acknowledged an allergy override on the order. A blocking match
     * stops the dose unless the nurse overrides with justification.
     *
     * @return an audit note when a match was overridden, else null
     */
    private String recheckAllergyAtAdministration(
            MedicationAdministration order, boolean override, String justification) {
        if (Boolean.TRUE.equals(order.getPrescribedDespiteAllergy())) return null;
        MedicationSafetyEngine.AllergyAssessment assessment =
                medicationSafetyEngine.assessAllergyForPrescription(
                        order.getVisit().getPatient(), order.getVisit(), order.getDrugName());
        if (!assessment.isBlocking()) return null;
        if (!override) {
            throw new ClinicalBusinessException(
                    "Administration blocked by the allergy safety check (recorded after "
                            + "prescribing): " + assessment.message()
                            + " A clinician may override with documented justification.");
        }
        requireJustification(justification);
        return "Allergy recheck overridden at administration: " + assessment.message();
    }

    private void assertWitness(MedicationAdministration order, String witnessName) {
        if (order.isRequiresWitness()
                && (witnessName == null || witnessName.isBlank())) {
            throw new ClinicalBusinessException(
                    (order.getProductType() != null && order.getProductType().isAlwaysRequiresWitness()
                            ? "Blood-product administrations require a second-clinician witness. "
                            : "This medication requires a second-clinician witness. ")
                            + "Enter the witness's name to proceed.");
        }
    }

    /**
     * Dose verification (the "right dose" of the five rights): when the
     * order carries a structured dose, the value the nurse confirms must
     * match it; a mismatch is rejected unless overridden with
     * justification.
     *
     * @return the verified value to record (null when neither side has one)
     */
    private BigDecimal verifyDose(MedicationAdministration order,
            BigDecimal requestedValue, String requestedUnit,
            boolean override, String justification) {
        if (order.getDoseValue() == null || requestedValue == null) {
            return requestedValue;
        }
        boolean valueMatches = order.getDoseValue().compareTo(requestedValue) == 0;
        boolean unitMatches = requestedUnit == null || order.getDoseUnit() == null
                || requestedUnit.trim().equalsIgnoreCase(order.getDoseUnit().trim());
        if (valueMatches && unitMatches) {
            return requestedValue;
        }
        if (!override) {
            throw new ClinicalBusinessException(String.format(
                    "Dose verification failed: the order is for %s %s but %s %s was entered. "
                            + "Re-check the order, or override with documented justification.",
                    order.getDoseValue().stripTrailingZeros().toPlainString(),
                    order.getDoseUnit() != null ? order.getDoseUnit() : "",
                    requestedValue.stripTrailingZeros().toPlainString(),
                    requestedUnit != null ? requestedUnit : ""));
        }
        requireJustification(justification);
        return requestedValue;
    }

    /** Department-visible record of any administration-time override. */
    private void raiseAdministrationOverrideAlert(
            MedicationAdministration order, MedicationDose dose, String justification) {
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(order.getVisit())
                .alertType(AlertType.MEDICATION_EMERGENCY_OVERRIDE)
                .severity(AlertSeverity.HIGH)
                .title("Administration override: " + order.getDrugName())
                .message(String.format(
                        "%s overrode an administration-time safety gate for '%s' (visit %s). "
                                + "Justification: %s",
                        dose.getGivenByName(), order.getDrugName(),
                        order.getVisit().getVisitNumber(), justification))
                .autoGenerated(true)
                .build();
        clinicalAlertRepository.save(alert);
        log.warn("ADMINISTRATION OVERRIDE — order:{} drug:{} by:{} justification:{}",
                order.getId(), order.getDrugName(), dose.getGivenByName(), justification);
    }

    /**
     * Cumulative daily-dose cap (hardening sprint). Evaluates whether
     * giving {@code doseValue doseUnit} of this order's drug NOW would
     * push the visit's same-drug 24-hour total past the formulary's
     * daily maximum — across ALL orders of that drug, because two
     * separate paracetamol orders still share one daily limit.
     *
     * <p>Adults use {@code adult_max_daily_dose_mg}; pediatric visits
     * use {@code pediatric_max_daily_dose_mg_per_kg} × the latest
     * triage weight (no weight on record → skipped; the prescribe-time
     * engine already warns about missing pediatric weight).
     *
     * <p>Consistent with the S2 unit rule: only evaluated when the
     * formulary's dose_unit is MG and the dose values normalise to a
     * mg-family unit (mg / g / mcg) — cross-unit arithmetic on
     * UNITS/IU/sachets would produce nonsense.
     *
     * @return a human-readable finding when the cap would be exceeded,
     *         null when within the cap or the check is not applicable
     */
    private String evaluateDailyDoseCap(
            MedicationAdministration order, BigDecimal doseValue, String doseUnit) {
        Double incomingMg = toMilligrams(doseValue, doseUnit);
        if (incomingMg == null || incomingMg <= 0) return null;

        DrugFormulary formulary = medicationSafetyEngine
                .lookupFormulary(order.getDrugName(), order.getVisit()).orElse(null);
        if (formulary == null) return null;
        String formularyUnit = formulary.getDoseUnit();
        boolean mgDosed = formularyUnit == null || formularyUnit.isBlank()
                || "MG".equalsIgnoreCase(formularyUnit.trim());
        if (!mgDosed) return null;

        double capMg;
        String capLabel;
        if (order.getVisit().isPediatric()) {
            Double perKg = formulary.getPediatricMaxDailyDoseMgPerKg();
            if (perKg == null || perKg <= 0) return null;
            Double weightKg = triageRecordRepository
                    .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(
                            order.getVisit().getId())
                    .map(com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord::getChildWeightKg)
                    .orElse(null);
            if (weightKg == null || weightKg <= 0) {
                log.warn("Daily-cap check skipped for '{}' — pediatric visit {} has no triage weight",
                        order.getDrugName(), order.getVisit().getVisitNumber());
                return null;
            }
            capMg = perKg * weightKg;
            capLabel = String.format("%.0f mg/24h (%.1f mg/kg/day × %.1f kg)",
                    capMg, perKg, weightKg);
        } else {
            Double adultCap = formulary.getAdultMaxDailyDoseMg();
            if (adultCap == null || adultCap <= 0) return null;
            capMg = adultCap;
            capLabel = String.format("%.0f mg/24h", capMg);
        }

        double priorMg = doseRepository
                .findGivenForVisitAndDrugSince(order.getVisit().getId(), order.getDrugName(),
                        Instant.now().minus(Duration.ofHours(24)))
                .stream()
                .map(d -> toMilligrams(d.getDoseValue(), d.getDoseUnit()))
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        double totalMg = priorMg + incomingMg;
        if (totalMg > capMg + 0.001) {
            return String.format(
                    "Daily maximum exceeded — %.0f mg of %s already given in the last 24 h; "
                            + "this dose (%.0f mg) would bring the total to %.0f mg, over the "
                            + "maximum of %s",
                    priorMg, order.getDrugName(), incomingMg, totalMg, capLabel);
        }
        return null;
    }

    /**
     * Normalise a dose to milligrams. Null/blank unit is treated as mg
     * (the frontend's structured-dose default). Non-mg-family units
     * (UNITS, IU, mL, sachets, …) return null — the caller skips the
     * cap rather than doing cross-unit arithmetic.
     */
    private Double toMilligrams(BigDecimal value, String unit) {
        if (value == null) return null;
        String u = unit == null || unit.isBlank() ? "mg" : unit.trim().toLowerCase();
        return switch (u) {
            case "mg" -> value.doubleValue();
            case "g" -> value.doubleValue() * 1000.0;
            case "mcg", "ug", "µg" -> value.doubleValue() / 1000.0;
            default -> null;
        };
    }

    private Double extractGateValue(VitalSigns vitals, MedicationAdministration order) {
        return switch (order.getGateParameter()) {
            case SYSTOLIC_BP -> vitals.getSystolicBp() != null
                    ? vitals.getSystolicBp().doubleValue() : null;
            case HEART_RATE -> vitals.getHeartRate() != null
                    ? vitals.getHeartRate().doubleValue() : null;
            case RESPIRATORY_RATE -> vitals.getRespiratoryRate() != null
                    ? vitals.getRespiratoryRate().doubleValue() : null;
            case SPO2 -> vitals.getSpo2() != null ? vitals.getSpo2().doubleValue() : null;
            case TEMPERATURE -> vitals.getTemperature();
            case PAIN_SCORE -> vitals.getPainScore() != null
                    ? vitals.getPainScore().doubleValue() : null;
        };
    }

    private MedicationDose createDose(MedicationAdministration order, DoseKind kind, Instant dueAt) {
        MedicationDose dose = MedicationDose.builder()
                .medication(order)
                .visit(order.getVisit())
                .kind(kind)
                .status(DoseStatus.DUE)
                .sequenceNumber(nextSequence(order))
                .dueAt(dueAt)
                .build();
        return doseRepository.save(dose);
    }

    private int nextSequence(MedicationAdministration order) {
        return (int) doseRepository.countByMedicationIdAndIsActiveTrue(order.getId()) + 1;
    }

    private MedicationDose findDoseOrThrow(UUID doseId) {
        return doseRepository.findByIdAndIsActiveTrue(doseId)
                .orElseThrow(() -> new ResourceNotFoundException("MedicationDose", "id", doseId));
    }

    private MedicationAdministration findOrderOrThrow(UUID orderId) {
        return medicationRepository.findByIdAndIsActiveTrue(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "MedicationAdministration", "id", orderId));
    }

    private void broadcastOrder(MedicationAdministration order) {
        try {
            if (order.getVisit() == null || order.getVisit().getHospital() == null) return;
            realTimeEventPublisher.publishMedication(
                    order.getVisit().getHospital().getId(), MedicationMapper.toResponse(order));
        } catch (Exception e) {
            log.warn("Failed to broadcast order {}: {}", order.getId(), e.getMessage());
        }
    }

    private User resolveCurrentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return null;
            Object principal = auth.getPrincipal();
            return (principal instanceof User user) ? user : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String formatUserName(User u) {
        if (u == null) return null;
        String first = u.getFirstName() != null ? u.getFirstName().trim() : "";
        String last = u.getLastName() != null ? u.getLastName().trim() : "";
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? u.getEmail() : joined;
    }

    private String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Build a Map payload for dose-level monitor broadcasts. */
    Map<String, Object> doseEventPayload(MedicationDose dose, String eventType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("doseId", dose.getId().toString());
        payload.put("medicationId", dose.getMedication().getId().toString());
        payload.put("visitId", dose.getVisit().getId().toString());
        payload.put("drugName", dose.getMedication().getDrugName());
        payload.put("dueAt", dose.getDueAt() != null ? dose.getDueAt().toString() : null);
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }
}
