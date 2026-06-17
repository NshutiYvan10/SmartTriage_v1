package com.smartTriage.smartTriage_server.module.lab.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.lab.dto.*;
import com.smartTriage.smartTriage_server.module.lab.engine.CriticalValueEngine;
import com.smartTriage.smartTriage_server.module.lab.engine.CriticalValueResult;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.mapper.LabOrderMapper;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LabOrderService — manages the full lab order lifecycle from ordering through
 * result recording, with integrated critical value detection and alerting.
 *
 * On critical result: creates ClinicalAlert with CRITICAL_LAB_RESULT type.
 * STAT turnaround monitoring is handled by LabTurnaroundMonitorService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabOrderService {

    private final LabOrderRepository labOrderRepository;
    private final InvestigationRepository investigationRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final CriticalValueEngine criticalValueEngine;
    private final VisitService visitService;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final HospitalRepository hospitalRepository;
    private final ShiftAssignmentService shiftAssignmentService;

    // ====================================================================
    // ORDER LAB
    // ====================================================================

    /**
     * Create a new lab order and a linked Investigation entity.
     */
    @Transactional
    public LabOrderResponse orderLab(UUID visitId, OrderLabRequest request) {
        Visit visit = visitService.findVisitOrThrow(visitId);

        // Stamp the ordering clinician from the authenticated principal so the
        // orderedBy FK + name are non-repudiable and the ordering-doctor user-targeted
        // alert push works for this path too (mirrors InvestigationService.orderInvestigation).
        User orderer = resolveCurrentUser();
        String ordererName = resolveActor(request.getOrderedByName());

        // Create linked Investigation entity
        Investigation investigation = Investigation.builder()
                .visit(visit)
                .investigationType(InvestigationType.LABORATORY)
                .testName(request.getTestName())
                .orderedBy(orderer)
                .orderedByName(ordererName)
                .orderedAt(Instant.now())
                .status(InvestigationStatus.ORDERED)
                .priority(request.getPriority().name())
                .notes(request.getNotes())
                .build();
        investigation = investigationRepository.save(investigation);

        // Generate order number
        String orderNumber = generateOrderNumber();

        // Create lab order
        LabOrder labOrder = LabOrder.builder()
                .visit(visit)
                .investigation(investigation)
                .orderNumber(orderNumber)
                .testName(request.getTestName())
                .testCode(request.getTestCode())
                .priority(request.getPriority())
                .status(LabOrderStatus.ORDERED)
                .orderedAt(Instant.now())
                .orderedByName(ordererName)
                .specimenType(request.getSpecimenType())
                .clinicalIndication(request.getClinicalIndication())
                .notes(request.getNotes())
                .build();

        labOrder = labOrderRepository.save(labOrder);

        log.info("Lab order created: {} — test: {} priority: {} visit: {}",
                orderNumber, request.getTestName(), request.getPriority(), visit.getVisitNumber());

        LabOrderResponse response = LabOrderMapper.toResponse(labOrder);
        broadcastLabOrder(labOrder, response);
        return response;
    }

    /**
     * Create a LabOrder linked to an Investigation row that has
     * already been persisted by another caller (typically
     * {@code InvestigationService.orderInvestigation} when the doctor
     * orders a laboratory-class investigation through the visit
     * detail page).
     *
     * <p>Why this exists: the doctor's order-entry path saves an
     * {@code Investigation} row but does not by itself reach the lab
     * tech — the lab inbox queries {@code lab_orders} and subscribes
     * to {@code /topic/lab/{hospitalId}}. Without a matching LabOrder
     * the order is silently dropped from the lab queue. This helper
     * creates the LabOrder + fires the broadcast so the order
     * appears in the lab inbox in real time, without forcing the
     * doctor's UI to switch to the separate {@code /lab/order}
     * endpoint (which requires fields the InvestigationPanel does
     * not collect).
     *
     * <p>The caller MUST have already saved the {@code investigation}
     * row; this method only creates the lab side and links to it.
     */
    @Transactional
    public LabOrderResponse attachLabOrderForInvestigation(
            Investigation investigation,
            LabPriority priority,
            String specimenType,
            String clinicalIndication,
            String notes) {
        Visit visit = investigation.getVisit();
        String orderNumber = generateOrderNumber();

        LabOrder labOrder = LabOrder.builder()
                .visit(visit)
                .investigation(investigation)
                .orderNumber(orderNumber)
                .testName(investigation.getTestName())
                .priority(priority != null ? priority : LabPriority.ROUTINE)
                .status(LabOrderStatus.ORDERED)
                .orderedAt(investigation.getOrderedAt() != null
                        ? investigation.getOrderedAt() : Instant.now())
                .orderedByName(investigation.getOrderedByName())
                .specimenType(specimenType)
                .clinicalIndication(clinicalIndication)
                .notes(notes)
                .build();

        labOrder = labOrderRepository.save(labOrder);
        log.info("Lab order {} attached to existing investigation {} — test: {} priority: {} visit: {}",
                orderNumber, investigation.getId(), investigation.getTestName(),
                labOrder.getPriority(), visit.getVisitNumber());

        LabOrderResponse response = LabOrderMapper.toResponse(labOrder);
        broadcastLabOrder(labOrder, response);
        return response;
    }

    // ====================================================================
    // WORKFLOW TRANSITIONS
    // ====================================================================

    @Transactional
    public LabOrderResponse collectSpecimen(UUID orderId, String collectedByName) {
        LabOrder order = findOrderOrThrow(orderId);
        requireStatus(order, LabOrderStatus.ORDERED, "collect specimen");

        order.setSpecimenCollectedAt(Instant.now());
        order.setSpecimenCollectedByName(resolveActor(collectedByName));
        order.setStatus(LabOrderStatus.SPECIMEN_COLLECTED);

        if (order.getInvestigation() != null) {
            order.getInvestigation().setSpecimenCollectedAt(Instant.now());
            order.getInvestigation().setStatus(InvestigationStatus.SPECIMEN_COLLECTED);
            investigationRepository.save(order.getInvestigation());
        }

        order = labOrderRepository.save(order);
        log.info("Specimen collected for order {} by {}", order.getOrderNumber(), order.getSpecimenCollectedByName());

        return broadcastAndMap(order);
    }

    /**
     * Lab tech acknowledges it has SEEN the order (distinct from receiving the specimen)
     * — gives the doctor visibility that the lab has picked it up. Records actor + time
     * from the authenticated principal; does NOT change the workflow status, so the order
     * stays in the inbox until a specimen is collected/received. First acknowledgement wins.
     */
    @Transactional
    public LabOrderResponse acknowledgeOrder(UUID orderId, String acknowledgedByName) {
        LabOrder order = findOrderOrThrow(orderId);
        requireStatusIn(order, "acknowledge order",
                LabOrderStatus.ORDERED, LabOrderStatus.SPECIMEN_COLLECTED, LabOrderStatus.RECEIVED_BY_LAB);
        if (order.getAcknowledgedByLabAt() == null) {
            order.setAcknowledgedByLabAt(Instant.now());
            order.setAcknowledgedByLabName(resolveActor(acknowledgedByName));
            order = labOrderRepository.save(order);
            log.info("Lab order {} acknowledged by {}", order.getOrderNumber(), order.getAcknowledgedByLabName());
        }
        return broadcastAndMap(order);
    }

    @Transactional
    public LabOrderResponse receiveInLab(UUID orderId) {
        return receiveInLab(orderId, new ReceiveSpecimenRequest());
    }

    /**
     * Lab tech accessions the specimen on receipt — writes the lab's
     * own barcode/sequence on the tube. Allowed from ORDERED (specimen
     * brought directly to the lab without bedside-collected step) or
     * SPECIMEN_COLLECTED.
     */
    @Transactional
    public LabOrderResponse receiveInLab(UUID orderId, ReceiveSpecimenRequest request) {
        LabOrder order = findOrderOrThrow(orderId);
        requireStatusIn(order, "receive specimen",
                LabOrderStatus.ORDERED, LabOrderStatus.SPECIMEN_COLLECTED);

        Instant now = Instant.now();
        if (order.getSpecimenCollectedAt() == null) {
            // Specimen arrived in lab without a separate "collected at bedside"
            // event — record both timestamps simultaneously.
            order.setSpecimenCollectedAt(now);
            if (request != null && request.getReceivedByName() != null) {
                order.setSpecimenCollectedByName(resolveActor(request.getReceivedByName()));
            }
        }
        order.setReceivedByLabAt(now);
        order.setStatus(LabOrderStatus.RECEIVED_BY_LAB);

        String accession = request != null ? request.getAccessionNumber() : null;
        if (accession == null || accession.isBlank()) {
            accession = generateAccessionNumber(order);
        }
        order.setAccessionNumber(accession);

        if (order.getInvestigation() != null) {
            order.getInvestigation().setStatus(InvestigationStatus.IN_PROGRESS);
            investigationRepository.save(order.getInvestigation());
        }

        order = labOrderRepository.save(order);
        log.info("Order {} received by lab — accession {}", order.getOrderNumber(), accession);

        return broadcastAndMap(order);
    }

    /**
     * Tech rejects the specimen on receipt (haemolysed, clotted,
     * mislabelled, etc.). Closes the order with status REJECTED and
     * fires an alert so the ordering doctor knows to redraw.
     */
    @Transactional
    public LabOrderResponse rejectSpecimen(UUID orderId, RejectSpecimenRequest request) {
        LabOrder order = findOrderOrThrow(orderId);
        // Allow rejection any time before the result is filed.
        if (order.getStatus() == LabOrderStatus.RESULTED
                || order.getStatus() == LabOrderStatus.CANCELLED
                || order.getStatus() == LabOrderStatus.REJECTED) {
            throw new ClinicalBusinessException(
                    "Cannot reject specimen for order " + order.getOrderNumber()
                            + " — status is " + order.getStatus());
        }

        Instant now = Instant.now();
        order.setStatus(LabOrderStatus.REJECTED);
        order.setRejectedAt(now);
        order.setRejectedByName(resolveActor(request.getRejectedByName()));
        order.setRejectionReason(request.getReason());
        order.setRejectionNotes(request.getNotes());

        if (order.getInvestigation() != null) {
            order.getInvestigation().setStatus(InvestigationStatus.CANCELLED);
            investigationRepository.save(order.getInvestigation());
        }

        // Fire an alert so the ordering doctor knows to redraw — its own type
        // (not CRITICAL_LAB_RESULT) so a redraw notice isn't mis-categorised as a
        // critical result nor pulled into the critical re-escalation set.
        EdZone zone = zoneOf(order.getVisit());
        User zoneDoctor = resolveZoneDoctor(order.getVisit(), zone);
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(order.getVisit())
                .alertType(AlertType.LAB_SPECIMEN_REJECTED)
                .severity(AlertSeverity.HIGH)
                .title("Lab specimen rejected: " + order.getTestName())
                .message(String.format(
                        "Specimen for order %s rejected — reason: %s%s. Please redraw.",
                        order.getOrderNumber(),
                        request.getReason().name(),
                        request.getNotes() != null && !request.getNotes().isBlank()
                                ? " (" + request.getNotes() + ")" : ""))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);

        order = labOrderRepository.save(order);
        publishOwnedLabAlert(alert, order, zone, zoneDoctor);
        log.warn("Order {} REJECTED by {} — reason: {}",
                order.getOrderNumber(), order.getRejectedByName(), request.getReason());

        return broadcastAndMap(order);
    }

    /**
     * Tech starts processing the specimen on the analyser / bench.
     */
    @Transactional
    public LabOrderResponse startProcessing(UUID orderId, String startedByName) {
        LabOrder order = findOrderOrThrow(orderId);
        requireStatus(order, LabOrderStatus.RECEIVED_BY_LAB, "start processing");

        order.setProcessingStartedAt(Instant.now());
        order.setStatus(LabOrderStatus.PROCESSING);

        order = labOrderRepository.save(order);
        log.info("Processing started for order {} by {}", order.getOrderNumber(), startedByName);

        return broadcastAndMap(order);
    }

    // ====================================================================
    // RECORD RESULT
    // ====================================================================

    @Transactional
    public LabOrderResponse recordResult(UUID orderId, RecordLabResultRequest request) {
        LabOrder order = findOrderOrThrow(orderId);
        // Allow recording from PROCESSING (normal path), RECEIVED_BY_LAB
        // (skip-processing shortcut), and AWAITING_VERIFICATION (re-entry
        // after the senior bounced the result back).
        requireStatusIn(order, "record result",
                LabOrderStatus.RECEIVED_BY_LAB,
                LabOrderStatus.PROCESSING,
                LabOrderStatus.AWAITING_VERIFICATION);

        Instant now = Instant.now();
        String enteredBy = resolveActor(request.getEnteredByName());
        order.setEnteredByName(enteredBy);
        order.setResultValue(request.getResultValue());
        order.setResultUnit(request.getResultUnit());
        order.setResultNumeric(request.getResultNumeric());
        order.setReferenceRangeMin(request.getReferenceRangeMin());
        order.setReferenceRangeMax(request.getReferenceRangeMax());

        // Resolve the catalog entry so the canonical unit / reference range / critical
        // thresholds can be applied.
        com.smartTriage.smartTriage_server.module.labcatalog.entity.LabTestCatalog catalog =
                criticalValueEngine.resolveCatalog(order);

        // A PRESENT entered unit that differs from the catalog's canonical unit means we
        // cannot safely compare this number against the catalog reference range OR
        // thresholds — so we skip the range/abnormal comparison and (below, if nothing
        // else flagged it) surface it for manual verification. A blank unit is assumed
        // canonical, so it does not count as a mismatch.
        boolean unitMismatch = catalog != null && catalog.getResultUnit() != null
                && order.getResultUnit() != null && !order.getResultUnit().isBlank()
                && !criticalValueEngine.unitCompatible(order.getResultUnit(), catalog.getResultUnit());

        // Catalog reference-range fallback (only when the unit is compatible — the range
        // is in the canonical unit) so the abnormal flag still works when the tech didn't
        // type a range.
        if (catalog != null && !unitMismatch) {
            if (order.getReferenceRangeMin() == null) order.setReferenceRangeMin(catalog.getReferenceLow());
            if (order.getReferenceRangeMax() == null) order.setReferenceRangeMax(catalog.getReferenceHigh());
        }

        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            String existing = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existing + "Result: " + request.getNotes());
        }

        // Check abnormal vs the effective reference range (typed or catalog-derived) —
        // skipped on a unit mismatch (cannot compare a value against a range in a
        // different unit).
        if (request.getResultNumeric() != null && !unitMismatch) {
            boolean abnormal = false;
            if (order.getReferenceRangeMin() != null && request.getResultNumeric() < order.getReferenceRangeMin()) {
                abnormal = true;
            }
            if (order.getReferenceRangeMax() != null && request.getResultNumeric() > order.getReferenceRangeMax()) {
                abnormal = true;
            }
            order.setAbnormal(abnormal);
        }

        // Run critical-value check (catalog-driven + unit-safe; a present-but-different
        // unit falls through to the unit-gated keyword rules, so a value in a different
        // KNOWN unit is still caught).
        CriticalValueResult criticalResult = criticalValueEngine.evaluateResult(order, catalog);
        if (criticalResult.isCritical()) {
            order.setCritical(true);
            order.setCriticalValueType(criticalResult.criticalValueType());

            String existing = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existing + "CRITICAL: " + criticalResult.description());
        }

        if (request.isSpecimenQualityConcern()) {
            String existing = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existing + "Specimen quality concern flagged at result entry");
        }

        // If the entered unit didn't match the expected unit AND nothing flagged it
        // critical, surface it for manual verification (abnormal + note) rather than
        // silently passing a value we could not auto-interpret.
        if (unitMismatch && !criticalResult.isCritical()) {
            order.setAbnormal(true);
            String existing = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existing + String.format(
                    "⚠ UNIT MISMATCH: result unit '%s' does not match expected '%s' — "
                    + "auto critical-value check skipped, verify manually.",
                    order.getResultUnit(), catalog.getResultUnit()));
            log.warn("Lab result unit mismatch on order {} — entered '{}', expected '{}'",
                    order.getOrderNumber(), order.getResultUnit(), catalog.getResultUnit());
        }

        // Decide release path:
        //   - hospital toggle ON + active HEAD_LAB_TECHNICIAN on staff
        //     AND result is high-risk (critical or quality-concern)
        //         → AWAITING_VERIFICATION
        //   - otherwise → RESULTED (self-verify, today's behaviour)
        boolean highRisk = order.isCritical() || request.isSpecimenQualityConcern();
        boolean verifyEnabled = isVerificationEnabledFor(order.getVisit().getHospital().getId());
        boolean gateThroughVerification = highRisk && verifyEnabled;

        if (gateThroughVerification) {
            order.setStatus(LabOrderStatus.AWAITING_VERIFICATION);
            order.setVerificationRequired(true);
            order.setVerificationTimeoutAt(now.plus(verificationTimeoutFor(order.getPriority())));
            // Keep result fields populated but DO NOT mark as resulted
            // — the doctor must not see this until a senior signs off.
            order = labOrderRepository.save(order);

            log.info("Result entered for order {} — gated AWAITING_VERIFICATION (timeout {} min)",
                    order.getOrderNumber(),
                    verificationTimeoutFor(order.getPriority()).toMinutes());

            return broadcastAndMap(order);
        }

        // Direct release (self-verify or low-risk).
        return finaliseResultedOrder(order, now, enteredBy, criticalResult, false);
    }

    /**
     * Common path that flips an order to RESULTED, files alerts, and
     * updates the linked Investigation. Called from the direct-release
     * branch of recordResult and from the verification/override/timeout
     * paths.
     */
    private LabOrderResponse finaliseResultedOrder(
            LabOrder order, Instant now, String verifierName,
            CriticalValueResult criticalResult, boolean alreadyHadCriticalAlert) {

        order.setResultedAt(now);
        order.setStatus(LabOrderStatus.RESULTED);
        order.setVerifiedAt(now);
        order.setVerifiedByName(verifierName);

        long turnaroundMinutes = Duration.between(order.getOrderedAt(), now).toMinutes();
        order.setTurnaroundMinutes((int) turnaroundMinutes);

        // Critical alert — fired only when transitioning to RESULTED so
        // the doctor isn't alerted while the result is still gated. If
        // the caller already created the alert (paranoid double-call
        // guard), skip.
        if (order.isCritical() && !alreadyHadCriticalAlert) {
            order.setCriticalValueNotifiedAt(now);
            CriticalValueResult cr = criticalResult != null
                    ? criticalResult
                    : criticalValueEngine.evaluateResult(order);
            createCriticalValueAlert(order, cr);
        }

        if (order.getInvestigation() != null) {
            Investigation inv = order.getInvestigation();
            inv.setResultedAt(now);
            inv.setResult(order.getResultValue());
            inv.setIsAbnormal(order.isAbnormal());
            inv.setIsCritical(order.isCritical());
            inv.setStatus(InvestigationStatus.RESULTED);
            investigationRepository.save(inv);

            // Non-critical results would otherwise land silently — the
            // doctor would have to refresh the Investigations tab to
            // notice them. Fire an INVESTIGATION_RESULTED alert so the
            // result reaches the existing alert pipeline. Critical
            // results already got the more specific CRITICAL_LAB_RESULT
            // alert above; skip them here to avoid double-notifying.
            if (!order.isCritical()) {
                createResultAvailableAlert(order, inv);
            }
        }

        order = labOrderRepository.save(order);
        log.info("Result released for order {} — value: {} critical: {} turnaround: {} min",
                order.getOrderNumber(), order.getResultValue(), order.isCritical(), turnaroundMinutes);

        return broadcastAndMap(order);
    }

    // ====================================================================
    // VERIFICATION (Phase 2)
    // ====================================================================

    /**
     * Senior tech verifies a pending result and releases it to the
     * doctor. The order flips to RESULTED and any critical alert
     * fires now (not at result-entry time).
     */
    @Transactional
    public LabOrderResponse verifyResult(UUID orderId, VerifyResultRequest request) {
        LabOrder order = findOrderOrThrow(orderId);
        requireStatus(order, LabOrderStatus.AWAITING_VERIFICATION, "verify result");

        String verifier = resolveActor(request != null ? request.getVerifiedByName() : null);
        if (request != null && request.getNotes() != null && !request.getNotes().isBlank()) {
            String existing = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existing + "Verifier note: " + request.getNotes());
        }
        return finaliseResultedOrder(order, Instant.now(), verifier, null, false);
    }

    /**
     * Senior tech rejects the result and bounces it back to the
     * junior. Status drops back to PROCESSING so the junior re-enters.
     * Result fields stay populated as the junior's draft.
     */
    @Transactional
    public LabOrderResponse rejectVerification(UUID orderId, RejectVerificationRequest request) {
        LabOrder order = findOrderOrThrow(orderId);
        requireStatus(order, LabOrderStatus.AWAITING_VERIFICATION, "reject verification");

        Instant now = Instant.now();
        order.setStatus(LabOrderStatus.PROCESSING);
        order.setVerificationTimeoutAt(null);
        order.setVerificationRejectionCount(order.getVerificationRejectionCount() + 1);
        order.setVerificationRejectionReason(request.getReason());
        order.setVerificationRejectedByName(resolveActor(request.getRejectedByName()));
        order.setVerificationRejectedAt(now);

        order = labOrderRepository.save(order);
        log.warn("Result for order {} REJECTED by senior {} — reason: {}",
                order.getOrderNumber(), order.getVerificationRejectedByName(), request.getReason());

        return broadcastAndMap(order);
    }

    /**
     * Junior tech emergency override — releases an
     * AWAITING_VERIFICATION result without senior sign-off. The
     * required reason is logged for audit.
     */
    @Transactional
    public LabOrderResponse overrideVerification(UUID orderId, OverrideVerificationRequest request) {
        LabOrder order = findOrderOrThrow(orderId);
        requireStatus(order, LabOrderStatus.AWAITING_VERIFICATION, "override verification");

        Instant now = Instant.now();
        String overrider = resolveActor(request.getOverrideByName());
        order.setVerificationOverride(true);
        order.setVerificationOverrideReason(request.getReason());
        order.setVerificationOverrideByName(overrider);
        order.setVerificationOverrideAt(now);

        log.warn("Verification BYPASSED for order {} by {} — reason: {}",
                order.getOrderNumber(), overrider, request.getReason());

        // A junior releasing an unverified result without senior sign-off is a
        // safety-gate bypass — make it auditable/visible as an OWNED alert (its own
        // type, not CRITICAL_LAB_RESULT) and push it to the zone/senior/charge nurse.
        EdZone zone = zoneOf(order.getVisit());
        User zoneDoctor = resolveZoneDoctor(order.getVisit(), zone);
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(order.getVisit())
                .alertType(AlertType.LAB_VERIFICATION_OVERRIDDEN)
                .severity(order.isCritical() ? AlertSeverity.CRITICAL : AlertSeverity.HIGH)
                .title("Lab verification overridden: " + order.getTestName())
                .message(String.format(
                        "%s released order %s (%s) WITHOUT senior verification%s. Reason: %s",
                        overrider,
                        order.getOrderNumber(),
                        order.getTestName(),
                        order.isCritical() ? " [CRITICAL result]" : "",
                        request.getReason()))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);
        publishOwnedLabAlert(alert, order, zone, zoneDoctor);

        return finaliseResultedOrder(order, now, overrider, null, false);
    }

    /**
     * Background scheduler — release any AWAITING_VERIFICATION orders
     * whose timeout has passed. Keeps patient care unblocked when no
     * senior is online during a shift.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 60_000)
    @Transactional
    public void autoReleaseTimedOutVerifications() {
        Instant now = Instant.now();
        List<LabOrder> timedOut = labOrderRepository.findVerificationTimeoutsBefore(now);
        for (LabOrder order : timedOut) {
            try {
                order.setVerificationAutoReleased(true);
                log.warn("Verification timeout — auto-releasing order {} (priority {}, waited {})",
                        order.getOrderNumber(),
                        order.getPriority(),
                        order.getVerificationTimeoutAt());
                finaliseResultedOrder(order, now, "(system: auto-release after timeout)", null, false);
            } catch (Exception e) {
                log.error("Failed to auto-release order {}: {}", order.getOrderNumber(), e.getMessage());
            }
        }
    }

    /**
     * Verification is enforced only when the hospital has the toggle
     * ON AND there is at least one active HEAD_LAB_TECHNICIAN on
     * staff. This prevents the gate from accidentally blocking
     * results at sites that don't yet have senior coverage.
     */
    private boolean isVerificationEnabledFor(UUID hospitalId) {
        var hospital = hospitalRepository.findById(hospitalId).orElse(null);
        if (hospital == null || !hospital.isTwoStepVerificationEnabled()) return false;
        long headTechs = labOrderRepository.countActiveHeadLabTechs(hospitalId);
        return headTechs > 0;
    }

    private static java.time.Duration verificationTimeoutFor(LabPriority priority) {
        return switch (priority) {
            case STAT     -> java.time.Duration.ofMinutes(5);
            case URGENT   -> java.time.Duration.ofMinutes(15);
            case ROUTINE  -> java.time.Duration.ofMinutes(60);
        };
    }

    // ====================================================================
    // ACKNOWLEDGE CRITICAL VALUE
    // ====================================================================

    /**
     * Doctor acknowledges a critical lab value with a read-back
     * attestation (JCI NPSG.02.03.01). The read-back text and contact
     * method are stored on the order row so an inspector can audit
     * how the panic value was communicated.
     */
    @Transactional
    public LabOrderResponse acknowledgeCriticalValue(UUID orderId, AcknowledgeCriticalRequest request) {
        LabOrder order = findOrderOrThrow(orderId);

        if (!order.isCritical()) {
            throw new ClinicalBusinessException("Order " + order.getOrderNumber() + " does not have a critical value");
        }

        if (order.getCriticalValueAcknowledgedAt() != null) {
            throw new ClinicalBusinessException("Critical value already acknowledged for order " + order.getOrderNumber());
        }

        order.setCriticalValueAcknowledgedAt(Instant.now());
        order.setCriticalValueNotifiedTo(
                resolveActor(request != null ? request.getAcknowledgedByName() : null));
        if (request != null) {
            order.setCriticalReadbackText(request.getReadbackText());
            order.setCriticalContactMethod(request.getContactMethod());
        }

        order = labOrderRepository.save(order);

        // Close the escalation loop: acknowledge the open CRITICAL_LAB_RESULT /
        // CRITICAL_VALUE_UNACKNOWLEDGED alerts for this visit so the time-critical
        // re-escalation scheduler does NOT re-page all-staff after the doctor has
        // already responded (avoids false alarms / alert fatigue).
        acknowledgeOpenCriticalLabAlerts(order);

        log.info("Critical value acknowledged for order {} by {} (method: {})",
                order.getOrderNumber(),
                order.getCriticalValueNotifiedTo(),
                order.getCriticalContactMethod());

        return broadcastAndMap(order);
    }

    // ====================================================================
    // CANCEL ORDER
    // ====================================================================

    @Transactional
    public LabOrderResponse cancelOrder(UUID orderId, String reason, String cancelledByName) {
        LabOrder order = findOrderOrThrow(orderId);

        if (order.getStatus() == LabOrderStatus.RESULTED) {
            throw new ClinicalBusinessException("Cannot cancel order " + order.getOrderNumber() + " — already resulted");
        }
        if (order.getStatus() == LabOrderStatus.CANCELLED
                || order.getStatus() == LabOrderStatus.REJECTED) {
            throw new ClinicalBusinessException(
                    "Order " + order.getOrderNumber() + " is already " + order.getStatus());
        }

        order.setCancelledAt(Instant.now());
        order.setCancelledByName(resolveActor(cancelledByName));
        order.setCancelReason(reason);
        order.setStatus(LabOrderStatus.CANCELLED);

        if (order.getInvestigation() != null) {
            order.getInvestigation().setStatus(InvestigationStatus.CANCELLED);
            investigationRepository.save(order.getInvestigation());
        }

        order = labOrderRepository.save(order);

        log.info("Order {} cancelled by {} — reason: {}", order.getOrderNumber(), order.getCancelledByName(), reason);

        return broadcastAndMap(order);
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    public Page<LabOrderResponse> getOrdersForVisit(UUID visitId, Pageable pageable) {
        return labOrderRepository
                .findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(visitId, pageable)
                .map(LabOrderMapper::toResponse)
                .map(LabOrderService::maskPreVerificationResult);
    }

    /**
     * A result that is still AWAITING_VERIFICATION must NOT be shown as a value on the
     * per-visit chart list — it is a junior's draft the senior hasn't signed off, and the
     * "doctor must not see it pre-verification" guarantee was previously enforced only at
     * the alert layer. Blank the result fields here (the lab tech sees the draft via the
     * dedicated verification queue, not this list).
     */
    private static LabOrderResponse maskPreVerificationResult(LabOrderResponse r) {
        if (r != null && r.getStatus() == LabOrderStatus.AWAITING_VERIFICATION) {
            r.setResultValue(null);
            r.setResultNumeric(null);
            r.setResultUnit(null);
            r.setAbnormal(false);
            r.setCritical(false);
            // notes can echo the draft value/critical description (recordResult folds them
            // in), so blank it too — otherwise the masked value leaks through notes.
            r.setNotes(null);
        }
        return r;
    }

    /** Does the investigation have an active LabOrder (i.e. the lab "owns" its lifecycle)? */
    public boolean hasActiveLabOrderForInvestigation(UUID investigationId) {
        return labOrderRepository.existsByInvestigation_IdAndIsActiveTrue(investigationId);
    }

    /** Investigation ids on a visit that have an active LabOrder — lets the chart show its
     *  own lifecycle actions only for investigations the lab is NOT driving. */
    public java.util.Set<UUID> investigationIdsWithActiveLabOrder(UUID visitId) {
        return new java.util.HashSet<>(labOrderRepository.findInvestigationIdsWithActiveLabOrderForVisit(visitId));
    }

    public Page<LabOrderResponse> getPendingOrders(UUID hospitalId, Pageable pageable) {
        return labOrderRepository
                .findPendingOrders(hospitalId, pageable)
                .map(LabOrderMapper::toResponse)
                .map(LabOrderService::maskPreVerificationResult);
    }

    public List<CriticalValueResponse> getCriticalResults(UUID hospitalId) {
        return labOrderRepository.findUnacknowledgedCriticalResults(hospitalId)
                .stream()
                .map(LabOrderMapper::toCriticalValueResponse)
                .collect(Collectors.toList());
    }

    public List<LabOrderResponse> getStatOrders(UUID hospitalId) {
        return labOrderRepository.findActiveStatOrders(hospitalId)
                .stream()
                .map(LabOrderMapper::toResponse)
                .collect(Collectors.toList());
    }

    /** Lab-tech inbox — orders waiting on lab action. */
    public List<LabOrderResponse> getInboxForLab(UUID hospitalId) {
        return labOrderRepository.findInboxForLab(hospitalId)
                .stream()
                .map(LabOrderMapper::toResponse)
                .collect(Collectors.toList());
    }

    /** Orders the tech is actively processing. */
    public List<LabOrderResponse> getInProgressForLab(UUID hospitalId) {
        return labOrderRepository.findInProgressForLab(hospitalId)
                .stream()
                .map(LabOrderMapper::toResponse)
                .collect(Collectors.toList());
    }

    /** Senior-tech verification queue (Phase 2). */
    public List<LabOrderResponse> getAwaitingVerification(UUID hospitalId) {
        return labOrderRepository.findAwaitingVerification(hospitalId)
                .stream()
                .map(LabOrderMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Workflow 2 refinement — paginated history view for the lab
     * tech. Returns RESULTED / CANCELLED / REJECTED orders (and
     * other completed states) so the tech can audit + re-look-up
     * previously processed work. Optional status filter; optional
     * free-text query against order number / test name / accession.
     * Sorted newest first.
     */
    public Page<LabOrderResponse> getHistoryForHospital(
            UUID hospitalId, LabOrderStatus status, String query, Pageable pageable) {
        String normalised = query != null ? query.trim() : "";
        if (normalised.isEmpty()) normalised = null;
        return labOrderRepository
                .searchHistory(hospitalId, status, normalised, pageable)
                .map(LabOrderMapper::toResponse);
    }

    // ====================================================================
    // INTERNAL HELPERS
    // ====================================================================

    public LabOrder findOrderOrThrow(UUID id) {
        return labOrderRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("LabOrder", "id", id));
    }

    /** State-machine guard — transition from a single expected status. */
    private void requireStatus(LabOrder order, LabOrderStatus expected, String action) {
        if (order.getStatus() != expected) {
            throw new ClinicalBusinessException(
                    "Cannot " + action + " for order " + order.getOrderNumber()
                            + " — current status is " + order.getStatus()
                            + ", expected " + expected);
        }
    }

    /** State-machine guard — transition allowed from any of these statuses. */
    private void requireStatusIn(LabOrder order, String action, LabOrderStatus... allowed) {
        for (LabOrderStatus s : allowed) {
            if (order.getStatus() == s) return;
        }
        throw new ClinicalBusinessException(
                "Cannot " + action + " for order " + order.getOrderNumber()
                        + " — current status is " + order.getStatus());
    }

    private String generateOrderNumber() {
        String datePrefix = "LAB-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = labOrderRepository.countByOrderNumberPrefix(datePrefix);
        return String.format("%s-%05d", datePrefix, count + 1);
    }

    /**
     * Accession number written by the lab on the tube — order-number
     * suffix is fine as a default until barcode integration arrives.
     */
    private String generateAccessionNumber(LabOrder order) {
        return "ACC-" + order.getOrderNumber().substring("LAB-".length());
    }

    /**
     * Broadcast the order to {@code /topic/lab/{hospitalId}} and
     * return the response DTO. Called from every state transition so
     * the lab-tech dashboard stays live without polling.
     */
    private LabOrderResponse broadcastAndMap(LabOrder order) {
        LabOrderResponse response = LabOrderMapper.toResponse(order);
        broadcastLabOrder(order, response);
        return response;
    }

    private void broadcastLabOrder(LabOrder order, LabOrderResponse response) {
        final UUID hospitalId = (order.getVisit() != null && order.getVisit().getHospital() != null)
                ? order.getVisit().getHospital().getId() : null;
        if (hospitalId == null) return;
        // Deferred to AFTER COMMIT so a rolled-back result/critical transition never
        // pushes a phantom (critical-flagged) row to the lab dashboard, matching the
        // doctor-alert push. Best-effort: a STOMP failure must not break the workflow.
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishLabOrder(hospitalId, response);
            } catch (Exception e) {
                log.warn("Failed to broadcast lab-order event for {}: {}",
                        order.getOrderNumber(), e.getMessage());
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

    /**
     * Prefer the authenticated principal's name over any client-supplied name, so
     * result authorship / verification / acknowledgement is non-repudiable (the
     * "who" can't be spoofed by the request body). Falls back to the client value
     * only when there is no authenticated user (background jobs, tests).
     */
    private String resolveActor(String fallback) {
        String name = formatUserName(resolveCurrentUser());
        return name != null ? name : fallback;
    }

    /** Canonical "First Last" (trimmed), falling back to email — mirrors
     *  InvestigationService.formatUserName so authorship strings are consistent
     *  and never a bare space. */
    private String formatUserName(User u) {
        if (u == null) return null;
        String first = u.getFirstName() != null ? u.getFirstName().trim() : "";
        String last = u.getLastName() != null ? u.getLastName().trim() : "";
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? u.getEmail() : joined;
    }

    /**
     * The accountable zone doctor for the patient's current zone, or null if none
     * is on shift / the zone is unknown. Used to OWN lab alerts.
     */
    private User resolveZoneDoctor(Visit visit, EdZone zone) {
        try {
            UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
            if (hospitalId == null || zone == null) return null;
            List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
            return doctors.isEmpty() ? null : doctors.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private EdZone zoneOf(Visit visit) {
        return visit.getCurrentTriageCategory() != null
                ? EdZone.fromTriageCategory(visit.getCurrentTriageCategory())
                : null;
    }

    /**
     * Push a saved lab ClinicalAlert to the people responsible for acting on it —
     * the zone board, the accountable zone doctor, the ORDERING doctor (resolved
     * from the linked Investigation), and the charge nurse(s) — over the doctor
     * alert pipeline (/topic/alerts/*). Deferred to AFTER COMMIT so a rolled-back
     * result never produces a phantom critical alert, and best-effort so a STOMP
     * failure never breaks the clinical transaction. Before this, lab alerts were
     * saved to the DB but only broadcast to the lab-tech inbox topic — the doctor's
     * alert feed received nothing.
     */
    private void publishOwnedLabAlert(ClinicalAlert alert, LabOrder order, EdZone zone, User zoneDoctor) {
        UUID hospitalId = order.getVisit().getHospital() != null
                ? order.getVisit().getHospital().getId() : null;
        if (hospitalId == null || alert == null) return;

        final var resp = ClinicalAlertMapper.toResponse(alert);
        final EdZone z = zone;
        final UUID zoneDoctorId = zoneDoctor != null ? zoneDoctor.getId() : null;
        final UUID orderingDoctorId =
                (order.getInvestigation() != null && order.getInvestigation().getOrderedBy() != null)
                        ? order.getInvestigation().getOrderedBy().getId() : null;
        final List<UUID> chargeNurseIds = shiftAssignmentService.getChargeNurse(hospitalId)
                .stream().map(User::getId).toList();

        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                if (z != null) realTimeEventPublisher.publishZoneAlert(hospitalId, z, resp);
                if (zoneDoctorId != null) realTimeEventPublisher.publishUserAlert(zoneDoctorId, resp);
                if (orderingDoctorId != null && !orderingDoctorId.equals(zoneDoctorId)) {
                    realTimeEventPublisher.publishUserAlert(orderingDoctorId, resp);
                }
                for (UUID cnId : chargeNurseIds) realTimeEventPublisher.publishUserAlert(cnId, resp);
            } catch (Exception e) {
                log.warn("Failed to publish owned lab alert {} for order {}: {}",
                        alert.getId(), order.getOrderNumber(), e.getMessage());
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

    /** The authenticated user, or null when there is no security context. */
    private User resolveCurrentUser() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) return user;
        } catch (Exception ignored) { /* no context */ }
        return null;
    }

    /**
     * Acknowledge the open lab critical-value alerts for a visit (CRITICAL_LAB_RESULT
     * and the monitor's CRITICAL_VALUE_UNACKNOWLEDGED escalation) when the doctor
     * read-back-acknowledges the value, so the time-critical escalation scheduler
     * stops re-paging an alert the clinician has already responded to.
     */
    private void acknowledgeOpenCriticalLabAlerts(LabOrder order) {
        try {
            // The CRITICAL_LAB_RESULT / CRITICAL_VALUE_UNACKNOWLEDGED alerts are
            // VISIT-scoped (no per-order link). If another resulted critical on the
            // same visit is still unacknowledged, leave the alerts open so its
            // escalation is not falsely suppressed — close the loop only once every
            // critical on the visit has been acknowledged.
            if (labOrderRepository.hasUnacknowledgedCriticalForVisit(order.getVisit().getId())) {
                return;
            }
            User acker = resolveCurrentUser();
            List<ClinicalAlert> open = clinicalAlertRepository
                    .findByVisitIdAndAlertTypeInAndIsAcknowledgedFalseAndIsActiveTrue(
                            order.getVisit().getId(),
                            EnumSet.of(AlertType.CRITICAL_LAB_RESULT, AlertType.CRITICAL_VALUE_UNACKNOWLEDGED));
            for (ClinicalAlert a : open) {
                a.setAcknowledged(true);
                a.setAcknowledgedAt(Instant.now());
                if (acker != null) a.setAcknowledgedBy(acker);
            }
            if (!open.isEmpty()) clinicalAlertRepository.saveAll(open);
        } catch (Exception e) {
            // Never let alert bookkeeping break the clinical acknowledgement.
            log.warn("Failed to acknowledge open critical lab alerts for order {}: {}",
                    order.getOrderNumber(), e.getMessage());
        }
    }

    private void createCriticalValueAlert(LabOrder order, CriticalValueResult criticalResult) {
        EdZone zone = zoneOf(order.getVisit());
        User zoneDoctor = resolveZoneDoctor(order.getVisit(), zone);

        // Description can be null if a re-evaluation no longer flags the (already-critical)
        // order — fall back to the persisted critical type / a generic note so the alert
        // message never contains a literal "null".
        String description = criticalResult != null && criticalResult.description() != null
                ? criticalResult.description()
                : (order.getCriticalValueType() != null
                        ? order.getCriticalValueType().getDescription()
                        : "Critical value flagged");

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(order.getVisit())
                .alertType(AlertType.CRITICAL_LAB_RESULT)
                .severity(AlertSeverity.CRITICAL)
                .title("CRITICAL LAB RESULT: " + order.getTestName())
                .message(String.format("Critical lab value for %s (Order %s): %s %s — %s. " +
                                "Immediate clinician acknowledgement required.",
                        order.getTestName(),
                        order.getOrderNumber(),
                        order.getResultValue(),
                        order.getResultUnit() != null ? order.getResultUnit() : "",
                        description))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .escalationTier(1)
                .autoGenerated(true)
                .build();

        alert = clinicalAlertRepository.save(alert);
        // Push to the zone board + accountable/ordering doctor + charge nurse so the
        // panic value reaches a clinician immediately, not only via the dashboard banner.
        publishOwnedLabAlert(alert, order, zone, zoneDoctor);

        log.warn("CRITICAL LAB ALERT created for order {} — {}", order.getOrderNumber(), description);
    }

    /**
     * Non-critical result-available alert. Mirrors the templating in
     * {@code InvestigationService.generateResultAlert} so abnormal-
     * but-not-critical and normal results push a notification to the
     * doctor instead of landing silently on the row. Severity scales:
     *   ABNORMAL → HIGH, NORMAL → MEDIUM.
     *
     * <p>Critical results never reach this path — they get the
     * dedicated CRITICAL_LAB_RESULT alert in
     * {@link #createCriticalValueAlert(LabOrder, CriticalValueResult)}.
     *
     * <p>If the alert pipeline ever changes, keep this method and
     * {@code InvestigationService.generateResultAlert} in sync.
     */
    private void createResultAvailableAlert(LabOrder order, Investigation investigation) {
        try {
            AlertSeverity severity = investigation.getIsAbnormal()
                    ? AlertSeverity.HIGH
                    : AlertSeverity.MEDIUM;

            String prefix = investigation.getIsAbnormal() ? "Abnormal " : "";
            String title = prefix + "Result: " + order.getTestName();
            String message = String.format(
                    "Lab result for '%s' (Order %s) is now available for visit %s.%s",
                    order.getTestName(),
                    order.getOrderNumber(),
                    order.getVisit().getVisitNumber(),
                    investigation.getIsAbnormal() ? " Abnormal value detected." : "");

            EdZone zone = zoneOf(order.getVisit());
            User zoneDoctor = resolveZoneDoctor(order.getVisit(), zone);

            ClinicalAlert alert = ClinicalAlert.builder()
                    .visit(order.getVisit())
                    .alertType(AlertType.INVESTIGATION_RESULTED)
                    .severity(severity)
                    .title(title)
                    .message(message)
                    .targetZone(zone)
                    .targetDoctor(zoneDoctor)
                    .autoGenerated(true)
                    .build();

            alert = clinicalAlertRepository.save(alert);
            // A returned result (even non-critical/abnormal) must reach the doctor's
            // alert feed — previously this row was saved but never pushed anywhere.
            publishOwnedLabAlert(alert, order, zone, zoneDoctor);
            log.info("INVESTIGATION_RESULTED alert created for order {} — test:'{}' severity:{}",
                    order.getOrderNumber(), order.getTestName(), severity);
        } catch (Exception e) {
            // Alert generation must never block result release.
            log.error("Failed to create result-available alert for order {}: {}",
                    order.getOrderNumber(), e.getMessage());
        }
    }
}
