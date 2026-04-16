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
                .orderedAt(Instant.now())
                .orderedByName(request.getOrderedByName())
                .specimenType(request.getSpecimenType())
                .notes(request.getNotes())
                .build();

        labOrder = labOrderRepository.save(labOrder);

        log.info("Lab order created: {} — test: {} priority: {} visit: {}",
                orderNumber, request.getTestName(), request.getPriority(), visit.getVisitNumber());

        return LabOrderMapper.toResponse(labOrder);
    }

    // ====================================================================
    // WORKFLOW TRANSITIONS
    // ====================================================================

    @Transactional
    public LabOrderResponse collectSpecimen(UUID orderId, String collectedByName) {
        LabOrder order = findOrderOrThrow(orderId);
        validateNotCancelledOrResulted(order);

        if (order.getSpecimenCollectedAt() != null) {
            throw new ClinicalBusinessException("Specimen already collected for order " + order.getOrderNumber());
        }

        order.setSpecimenCollectedAt(Instant.now());
        order.setSpecimenCollectedByName(collectedByName);

        // Update linked investigation
        if (order.getInvestigation() != null) {
            order.getInvestigation().setSpecimenCollectedAt(Instant.now());
            order.getInvestigation().setStatus(InvestigationStatus.SPECIMEN_COLLECTED);
            investigationRepository.save(order.getInvestigation());
        }

        order = labOrderRepository.save(order);
        log.info("Specimen collected for order {}", order.getOrderNumber());

        return LabOrderMapper.toResponse(order);
    }

    @Transactional
    public LabOrderResponse receiveInLab(UUID orderId) {
        LabOrder order = findOrderOrThrow(orderId);
        validateNotCancelledOrResulted(order);

        order.setReceivedByLabAt(Instant.now());

        // Update linked investigation status
        if (order.getInvestigation() != null) {
            order.getInvestigation().setStatus(InvestigationStatus.IN_PROGRESS);
            investigationRepository.save(order.getInvestigation());
        }

        order = labOrderRepository.save(order);
        log.info("Order {} received by lab", order.getOrderNumber());

        return LabOrderMapper.toResponse(order);
    }

    // ====================================================================
    // RECORD RESULT
    // ====================================================================

    @Transactional
    public LabOrderResponse recordResult(UUID orderId, RecordLabResultRequest request) {
        LabOrder order = findOrderOrThrow(orderId);
        validateNotCancelledOrResulted(order);

        Instant now = Instant.now();
        order.setResultedAt(now);
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

        order = labOrderRepository.save(order);

        log.info("Result recorded for order {} — value: {} critical: {} turnaround: {} min",
                order.getOrderNumber(), request.getResultValue(), order.isCritical(), turnaroundMinutes);

        return LabOrderMapper.toResponse(order);
    }

    // ====================================================================
    // ACKNOWLEDGE CRITICAL VALUE
    // ====================================================================

    @Transactional
    public LabOrderResponse acknowledgeCriticalValue(UUID orderId, String acknowledgedBy) {
        LabOrder order = findOrderOrThrow(orderId);

        if (!order.isCritical()) {
            throw new ClinicalBusinessException("Order " + order.getOrderNumber() + " does not have a critical value");
        }

        if (order.getCriticalValueAcknowledgedAt() != null) {
            throw new ClinicalBusinessException("Critical value already acknowledged for order " + order.getOrderNumber());
        }

        order.setCriticalValueAcknowledgedAt(Instant.now());
        order.setCriticalValueNotifiedTo(acknowledgedBy);

        order = labOrderRepository.save(order);

        log.info("Critical value acknowledged for order {} by {}", order.getOrderNumber(), acknowledgedBy);

        return LabOrderMapper.toResponse(order);
    }

    // ====================================================================
    // CANCEL ORDER
    // ====================================================================

    @Transactional
    public LabOrderResponse cancelOrder(UUID orderId, String reason, String cancelledByName) {
        LabOrder order = findOrderOrThrow(orderId);

        if (order.getResultedAt() != null) {
            throw new ClinicalBusinessException("Cannot cancel order " + order.getOrderNumber() + " — already resulted");
        }
        if (order.getCancelledAt() != null) {
            throw new ClinicalBusinessException("Order " + order.getOrderNumber() + " is already cancelled");
        }

        order.setCancelledAt(Instant.now());
        order.setCancelledByName(cancelledByName);
        order.setCancelReason(reason);

        // Update linked Investigation
        if (order.getInvestigation() != null) {
            order.getInvestigation().setStatus(InvestigationStatus.CANCELLED);
            investigationRepository.save(order.getInvestigation());
        }

        order = labOrderRepository.save(order);

        log.info("Order {} cancelled by {} — reason: {}", order.getOrderNumber(), cancelledByName, reason);

        return LabOrderMapper.toResponse(order);
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

    // ====================================================================
    // INTERNAL HELPERS
    // ====================================================================

    public LabOrder findOrderOrThrow(UUID id) {
        return labOrderRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("LabOrder", "id", id));
    }

    private void validateNotCancelledOrResulted(LabOrder order) {
        if (order.getCancelledAt() != null) {
            throw new ClinicalBusinessException("Order " + order.getOrderNumber() + " is cancelled");
        }
        if (order.getResultedAt() != null) {
            throw new ClinicalBusinessException("Order " + order.getOrderNumber() + " already has results");
        }
    }

    private String generateOrderNumber() {
        String datePrefix = "LAB-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = labOrderRepository.countByOrderNumberPrefix(datePrefix);
        return String.format("%s-%05d", datePrefix, count + 1);
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
