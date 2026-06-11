package com.smartTriage.smartTriage_server.module.medication.mapper;

import com.smartTriage.smartTriage_server.module.medication.dto.MedicationResponse;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;

/**
 * Maps MedicationAdministration entities to response DTOs.
 */
public final class MedicationMapper {

    private MedicationMapper() {}

    public static MedicationResponse toResponse(MedicationAdministration med) {
        return MedicationResponse.builder()
                .id(med.getId())
                .visitId(med.getVisit().getId())
                .drugName(med.getDrugName())
                .dose(med.getDose())
                .route(med.getRoute())
                .frequency(med.getFrequency())
                .priority(med.getPriority())
                .priorityLabel(med.getPriority() != null ? med.getPriority().getLabel() : null)
                .prescribedById(med.getPrescribedBy() != null ? med.getPrescribedBy().getId() : null)
                .prescribedByName(med.getPrescribedByName())
                .prescribedAt(med.getPrescribedAt())
                .administeredById(med.getAdministeredBy() != null ? med.getAdministeredBy().getId() : null)
                .administeredByName(med.getAdministeredByName())
                .administeredAt(med.getAdministeredAt())
                .countersignedById(med.getCountersignedBy() != null ? med.getCountersignedBy().getId() : null)
                .countersignedByName(med.getCountersignedByName())
                .countersignedAt(med.getCountersignedAt())
                .status(med.getStatus())
                .notes(med.getNotes())
                .prescribedDespiteAllergy(med.getPrescribedDespiteAllergy())
                .allergyOverrideMatches(med.getAllergyOverrideMatches())
                .allergyOverrideAcknowledgedAt(med.getAllergyOverrideAcknowledgedAt())
                .prescribedDespiteInteraction(med.getPrescribedDespiteInteraction())
                .interactionOverrideMatches(med.getInteractionOverrideMatches())
                .interactionOverrideAcknowledgedAt(med.getInteractionOverrideAcknowledgedAt())
                // Typed orders (V67)
                .prescriptionType(med.getPrescriptionType())
                .productType(med.getProductType())
                .productDetail(med.getProductDetail())
                .doseValue(med.getDoseValue())
                .doseUnit(med.getDoseUnit())
                .startAt(med.getStartAt())
                .intervalHours(med.getIntervalHours())
                .endAt(med.getEndAt())
                .maxDoses(med.getMaxDoses())
                .prnIndication(med.getPrnIndication())
                .prnMinIntervalHours(med.getPrnMinIntervalHours())
                .prnMaxDosesPerDay(med.getPrnMaxDosesPerDay())
                .gateParameter(med.getGateParameter())
                .gateComparator(med.getGateComparator())
                .gateThreshold(med.getGateThreshold())
                .rateValue(med.getRateValue())
                .rateUnit(med.getRateUnit())
                .approvalRequired(med.isApprovalRequired())
                .approvedByName(med.getApprovedByName())
                .approvedAt(med.getApprovedAt())
                .approvalNote(med.getApprovalNote())
                .emergencyOverride(med.isEmergencyOverride())
                .emergencyJustification(med.getEmergencyJustification())
                .requiresWitness(med.isRequiresWitness())
                .discontinuedAt(med.getDiscontinuedAt())
                .discontinuedByName(med.getDiscontinuedByName())
                .discontinueReason(med.getDiscontinueReason())
                .completedAt(med.getCompletedAt())
                .supersedesId(med.getSupersedesId())
                .supersededById(med.getSupersededById())
                .createdAt(med.getCreatedAt())
                .updatedAt(med.getUpdatedAt())
                .build();
    }
}
