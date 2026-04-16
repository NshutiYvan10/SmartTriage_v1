package com.smartTriage.smartTriage_server.module.lab.mapper;

import com.smartTriage.smartTriage_server.module.lab.dto.CriticalValueResponse;
import com.smartTriage.smartTriage_server.module.lab.dto.LabOrderResponse;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;

import java.time.Duration;
import java.time.Instant;

/**
 * Maps LabOrder entities to response DTOs.
 */
public final class LabOrderMapper {

    private LabOrderMapper() {}

    public static LabOrderResponse toResponse(LabOrder order) {
        return LabOrderResponse.builder()
                .id(order.getId())
                .visitId(order.getVisit().getId())
                .investigationId(order.getInvestigation() != null ? order.getInvestigation().getId() : null)
                .orderNumber(order.getOrderNumber())
                .testName(order.getTestName())
                .testCode(order.getTestCode())
                .priority(order.getPriority())
                .orderedAt(order.getOrderedAt())
                .orderedByName(order.getOrderedByName())
                .specimenType(order.getSpecimenType())
                .specimenCollectedAt(order.getSpecimenCollectedAt())
                .specimenCollectedByName(order.getSpecimenCollectedByName())
                .receivedByLabAt(order.getReceivedByLabAt())
                .processingStartedAt(order.getProcessingStartedAt())
                .resultedAt(order.getResultedAt())
                .resultValue(order.getResultValue())
                .resultUnit(order.getResultUnit())
                .resultNumeric(order.getResultNumeric())
                .referenceRangeMin(order.getReferenceRangeMin())
                .referenceRangeMax(order.getReferenceRangeMax())
                .isAbnormal(order.isAbnormal())
                .isCritical(order.isCritical())
                .criticalValueType(order.getCriticalValueType())
                .criticalValueNotifiedAt(order.getCriticalValueNotifiedAt())
                .criticalValueNotifiedTo(order.getCriticalValueNotifiedTo())
                .criticalValueAcknowledgedAt(order.getCriticalValueAcknowledgedAt())
                .turnaroundMinutes(order.getTurnaroundMinutes())
                .notes(order.getNotes())
                .cancelledAt(order.getCancelledAt())
                .cancelledByName(order.getCancelledByName())
                .cancelReason(order.getCancelReason())
                .status(deriveStatus(order))
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    public static CriticalValueResponse toCriticalValueResponse(LabOrder order) {
        long minutesSinceResult = 0;
        if (order.getResultedAt() != null) {
            minutesSinceResult = Duration.between(order.getResultedAt(), Instant.now()).toMinutes();
        }

        return CriticalValueResponse.builder()
                .labOrderId(order.getId())
                .visitId(order.getVisit().getId())
                .orderNumber(order.getOrderNumber())
                .testName(order.getTestName())
                .priority(order.getPriority())
                .resultValue(order.getResultValue())
                .resultUnit(order.getResultUnit())
                .resultNumeric(order.getResultNumeric())
                .criticalValueType(order.getCriticalValueType())
                .criticalDescription(order.getNotes())
                .resultedAt(order.getResultedAt())
                .criticalValueNotifiedAt(order.getCriticalValueNotifiedAt())
                .criticalValueNotifiedTo(order.getCriticalValueNotifiedTo())
                .minutesSinceResult(minutesSinceResult)
                .build();
    }

    /**
     * Derive human-readable status from lab order timestamps.
     */
    private static String deriveStatus(LabOrder order) {
        if (order.getCancelledAt() != null) {
            return "CANCELLED";
        }
        if (order.getResultedAt() != null) {
            return "RESULTED";
        }
        if (order.getProcessingStartedAt() != null) {
            return "PROCESSING";
        }
        if (order.getReceivedByLabAt() != null) {
            return "RECEIVED_BY_LAB";
        }
        if (order.getSpecimenCollectedAt() != null) {
            return "SPECIMEN_COLLECTED";
        }
        return "ORDERED";
    }
}
