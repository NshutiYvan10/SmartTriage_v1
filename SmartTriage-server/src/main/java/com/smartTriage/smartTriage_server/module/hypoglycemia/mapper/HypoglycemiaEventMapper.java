package com.smartTriage.smartTriage_server.module.hypoglycemia.mapper;

import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.HypoglycemiaEventResponse;
import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;

/**
 * Mapper for HypoglycemiaEvent entity to response DTO.
 */
public final class HypoglycemiaEventMapper {

    private HypoglycemiaEventMapper() {
    }

    public static HypoglycemiaEventResponse toResponse(HypoglycemiaEvent event) {
        HypoglycemiaEventResponse.HypoglycemiaEventResponseBuilder builder = HypoglycemiaEventResponse.builder()
                .id(event.getId())
                .detectedAt(event.getDetectedAt())
                .glucoseLevel(event.getGlucoseLevel())
                .triggerReason(event.getTriggerReason())
                .severity(event.getSeverity())
                .glucoseSource(event.getGlucoseSource())
                .neonatal(event.isNeonatal())
                .detectedByName(event.getDetectedByName())
                .recheckDueAt(event.getRecheckDueAt())
                .treatmentGiven(event.getTreatmentGiven())
                .treatmentGivenAt(event.getTreatmentGivenAt())
                .treatmentGivenByName(event.getTreatmentGivenByName())
                .repeatGlucoseLevel(event.getRepeatGlucoseLevel())
                .repeatGlucoseAt(event.getRepeatGlucoseAt())
                .resolved(event.isResolved())
                .resolvedAt(event.getResolvedAt())
                .resolvedByName(event.getResolvedByName())
                .notes(event.getNotes())
                .createdAt(event.getCreatedAt());

        if (event.getVisit() != null) {
            builder.visitId(event.getVisit().getId());
            builder.visitNumber(event.getVisit().getVisitNumber());
            builder.currentZone(event.getVisit().getCurrentEdZone() != null
                    ? event.getVisit().getCurrentEdZone().name() : null);
            if (event.getVisit().getCurrentBed() != null) {
                builder.currentBedLabel(event.getVisit().getCurrentBed().getCode());
            }
            if (event.getVisit().getPatient() != null) {
                builder.patientName(
                        event.getVisit().getPatient().getFirstName() + " " +
                                event.getVisit().getPatient().getLastName());
            }
        }

        return builder.build();
    }
}
