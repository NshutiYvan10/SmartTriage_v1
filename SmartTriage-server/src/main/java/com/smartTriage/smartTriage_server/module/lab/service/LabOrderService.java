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
        requireStatusIn(order, "record result",
                LabOrderStatus.RECEIVED_BY_LAB, LabOrderStatus.PROCESSING);

        Instant now = Instant.now();
        order.setResultedAt(now);
        order.setStatus(LabOrderStatus.RESULTED);
        order.setEnteredByName(request.getEnteredByName());
        // Phase 1: self-verify — same actor enters and verifies.
        // Phase 2 will gate this behind a HEAD_LAB_TECHNICIAN role.
        order.setVerifiedAt(now);
        order.setVerifiedByName(request.getEnteredByName());
        order.setResultValue(request.getResultValue());
        order.setResultUnit(request.getResultUnit());
        order.setResultNumeric(request.getResultNumeric());
        order.setReferenceRangeMin(request.getReferenceRangeMin());
        order.setReferenceRangeMax(request.getReferenceRangeMax());

        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            String existing = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existing + "Result: " + request.getNotes());
        }

        // Calculate turnaround time
        long turnaroundMinutes = Duration.between(order.getOrderedAt(), now).toMinutes();
        order.setTurnaroundMinutes((int) turnaroundMinutes);

        // Check for abnormal values against reference range
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

        // Run critical value check
        CriticalValueResult criticalResult = criticalValueEngine.evaluateResult(order);
        if (criticalResult.isCritical()) {
            order.setCritical(true);
            order.setCriticalValueType(criticalResult.criticalValueType());
            order.setCriticalValueNotifiedAt(now);

            // Append critical value description to notes
            String existing = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existing + "CRITICAL: " + criticalResult.description());

            // Create clinical alert
            createCriticalValueAlert(order, criticalResult);
        }

        // Update linked Investigation entity
        if (order.getInvestigation() != null) {
            Investigation inv = order.getInvestigation();
            inv.setResultedAt(now);
            inv.setResult(request.getResultValue());
            inv.setIsAbnormal(order.isAbnormal());
            inv.setIsCritical(order.isCritical());
            inv.setStatus(InvestigationStatus.RESULTED);
            investigationRepository.save(inv);
        }

        if (request.isSpecimenQualityConcern()) {
            String existing = order.getNotes() != null ? order.getNotes() + " | " : "";
            order.setNotes(existing + "Specimen quality concern flagged at result entry");
        }

        order = labOrderRepository.save(order);

        log.info("Result recorded for order {} — value: {} critical: {} turnaround: {} min",
                order.getOrderNumber(), request.getResultValue(), order.isCritical(), turnaroundMinutes);

        return broadcastAndMap(order);
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
}
