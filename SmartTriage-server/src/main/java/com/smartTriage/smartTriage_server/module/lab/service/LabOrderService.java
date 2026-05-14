package com.smartTriage.smartTriage_server.module.lab.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
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
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    // ====================================================================
    // ORDER LAB
    // ====================================================================

    /**
     * Create a new lab order and a linked Investigation entity.
     */
    @Transactional
    public LabOrderResponse orderLab(UUID visitId, OrderLabRequest request) {
        Visit visit = visitService.findVisitOrThrow(visitId);

        // Create linked Investigation entity
        Investigation investigation = Investigation.builder()
                .visit(visit)
                .investigationType(InvestigationType.LABORATORY)
                .testName(request.getTestName())
                .orderedByName(request.getOrderedByName())
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
                .orderedByName(request.getOrderedByName())
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
        order.setSpecimenCollectedByName(collectedByName);
        order.setStatus(LabOrderStatus.SPECIMEN_COLLECTED);

        if (order.getInvestigation() != null) {
            order.getInvestigation().setSpecimenCollectedAt(Instant.now());
            order.getInvestigation().setStatus(InvestigationStatus.SPECIMEN_COLLECTED);
            investigationRepository.save(order.getInvestigation());
        }

        order = labOrderRepository.save(order);
        log.info("Specimen collected for order {} by {}", order.getOrderNumber(), collectedByName);

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
                order.setSpecimenCollectedByName(request.getReceivedByName());
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
        order.setRejectedByName(request.getRejectedByName());
        order.setRejectionReason(request.getReason());
        order.setRejectionNotes(request.getNotes());

        if (order.getInvestigation() != null) {
            order.getInvestigation().setStatus(InvestigationStatus.CANCELLED);
            investigationRepository.save(order.getInvestigation());
        }

        // Fire an alert so the ordering doctor knows to redraw.
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(order.getVisit())
                .alertType(AlertType.CRITICAL_LAB_RESULT) // re-use type; severity differentiates
                .severity(AlertSeverity.HIGH)
                .title("Lab specimen rejected: " + order.getTestName())
                .message(String.format(
                        "Specimen for order %s rejected — reason: %s%s. Please redraw.",
                        order.getOrderNumber(),
                        request.getReason().name(),
                        request.getNotes() != null && !request.getNotes().isBlank()
                                ? " (" + request.getNotes() + ")" : ""))
                .autoGenerated(true)
                .build();
        clinicalAlertRepository.save(alert);

        order = labOrderRepository.save(order);
        log.warn("Order {} REJECTED by {} — reason: {}",
                order.getOrderNumber(), request.getRejectedByName(), request.getReason());

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
        order.setEnteredByName(request.getEnteredByName());
        order.setResultValue(request.getResultValue());
        order.setResultUnit(request.getResultUnit());
        order.setResultNumeric(request.getResultNumeric());
        order.setReferenceRangeMin(request.getReferenceRangeMin());
        order.setReferenceRangeMax(request.getReferenceRangeMax());

        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            String existing = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existing + "Result: " + request.getNotes());
        }

        // Check abnormal vs reference range
        if (request.getResultNumeric() != null) {
            boolean abnormal = false;
            if (request.getReferenceRangeMin() != null && request.getResultNumeric() < request.getReferenceRangeMin()) {
                abnormal = true;
            }
            if (request.getReferenceRangeMax() != null && request.getResultNumeric() > request.getReferenceRangeMax()) {
                abnormal = true;
            }
            order.setAbnormal(abnormal);
        }

        // Run critical-value check (sets isCritical + criticalValueType)
        CriticalValueResult criticalResult = criticalValueEngine.evaluateResult(order);
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
        return finaliseResultedOrder(order, now, request.getEnteredByName(), criticalResult, false);
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

        String verifier = request != null ? request.getVerifiedByName() : null;
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
        order.setVerificationRejectedByName(request.getRejectedByName());
        order.setVerificationRejectedAt(now);

        order = labOrderRepository.save(order);
        log.warn("Result for order {} REJECTED by senior {} — reason: {}",
                order.getOrderNumber(), request.getRejectedByName(), request.getReason());

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
        order.setVerificationOverride(true);
        order.setVerificationOverrideReason(request.getReason());
        order.setVerificationOverrideByName(request.getOverrideByName());
        order.setVerificationOverrideAt(now);

        log.warn("Verification BYPASSED for order {} by {} — reason: {}",
                order.getOrderNumber(),
                request.getOverrideByName(),
                request.getReason());

        return finaliseResultedOrder(order, now, request.getOverrideByName(), null, false);
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
        if (request != null) {
            if (request.getAcknowledgedByName() != null) {
                order.setCriticalValueNotifiedTo(request.getAcknowledgedByName());
            }
            order.setCriticalReadbackText(request.getReadbackText());
            order.setCriticalContactMethod(request.getContactMethod());
        }

        order = labOrderRepository.save(order);

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
        order.setCancelledByName(cancelledByName);
        order.setCancelReason(reason);
        order.setStatus(LabOrderStatus.CANCELLED);

        if (order.getInvestigation() != null) {
            order.getInvestigation().setStatus(InvestigationStatus.CANCELLED);
            investigationRepository.save(order.getInvestigation());
        }

        order = labOrderRepository.save(order);

        log.info("Order {} cancelled by {} — reason: {}", order.getOrderNumber(), cancelledByName, reason);

        return broadcastAndMap(order);
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    public Page<LabOrderResponse> getOrdersForVisit(UUID visitId, Pageable pageable) {
        return labOrderRepository
                .findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(visitId, pageable)
                .map(LabOrderMapper::toResponse);
    }

    public Page<LabOrderResponse> getPendingOrders(UUID hospitalId, Pageable pageable) {
        return labOrderRepository
                .findPendingOrders(hospitalId, pageable)
                .map(LabOrderMapper::toResponse);
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
        try {
            UUID hospitalId = order.getVisit().getHospital().getId();
            realTimeEventPublisher.publishLabOrder(hospitalId, response);
        } catch (Exception e) {
            // Never let a broadcast failure roll back the workflow transition.
            log.warn("Failed to broadcast lab-order event for {}: {}",
                    order.getOrderNumber(), e.getMessage());
        }
    }

    private void createCriticalValueAlert(LabOrder order, CriticalValueResult criticalResult) {
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
                        criticalResult.description()))
                .autoGenerated(true)
                .build();

        clinicalAlertRepository.save(alert);

        log.warn("CRITICAL LAB ALERT created for order {} — {}", order.getOrderNumber(), criticalResult.description());
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

            EdZone zone = order.getVisit().getCurrentTriageCategory() != null
                    ? EdZone.fromTriageCategory(order.getVisit().getCurrentTriageCategory())
                    : null;

            ClinicalAlert alert = ClinicalAlert.builder()
                    .visit(order.getVisit())
                    .alertType(AlertType.INVESTIGATION_RESULTED)
                    .severity(severity)
                    .title(title)
                    .message(message)
                    .targetZone(zone)
                    .autoGenerated(true)
                    .build();

            clinicalAlertRepository.save(alert);
            log.info("INVESTIGATION_RESULTED alert created for order {} — test:'{}' severity:{}",
                    order.getOrderNumber(), order.getTestName(), severity);
        } catch (Exception e) {
            // Alert generation must never block result release.
            log.error("Failed to create result-available alert for order {}: {}",
                    order.getOrderNumber(), e.getMessage());
        }
    }
}
