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
                .createdAt(med.getCreatedAt())
                .updatedAt(med.getUpdatedAt())
                .build();
    }
}
