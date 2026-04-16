package com.smartTriage.smartTriage_server.module.icu.mapper;

import com.smartTriage.smartTriage_server.module.icu.dto.IcuEscalationResponse;
import com.smartTriage.smartTriage_server.module.icu.entity.IcuEscalation;

/**
 * Mapper for converting IcuEscalation entities to response DTOs.
 */
public final class IcuEscalationMapper {

    private IcuEscalationMapper() {
    }

    public static IcuEscalationResponse toResponse(IcuEscalation escalation) {
        IcuEscalationResponse.IcuEscalationResponseBuilder builder = IcuEscalationResponse.builder()
                .id(escalation.getId())
                .escalationReason(escalation.getEscalationReason())
                .triggerType(escalation.getTriggerType())
                .escalatedAt(escalation.getEscalatedAt())
                .escalatedByName(escalation.getEscalatedByName())
                .automatic(escalation.isAutomatic())
                .icuTeamNotifiedAt(escalation.getIcuTeamNotifiedAt())
                .icuConsultant(escalation.getIcuConsultant())
                .icuRespondedAt(escalation.getIcuRespondedAt())
                .icuResponseMinutes(escalation.getIcuResponseMinutes())
                .icuBedAvailable(escalation.getIcuBedAvailable())
                .icuBedNumber(escalation.getIcuBedNumber())
                .icuBedAssignedAt(escalation.getIcuBedAssignedAt())
                .stabilizationStartedAt(escalation.getStabilizationStartedAt())
                .stabilizationNotes(escalation.getStabilizationNotes())
                .intubationRequired(escalation.getIntubationRequired())
                .vasopressorsRequired(escalation.getVasopressorsRequired())
                .mechanicalVentilation(escalation.getMechanicalVentilation())
                .status(escalation.getStatus())
                .declineReason(escalation.getDeclineReason())
                .transferredAt(escalation.getTransferredAt())
                .alternativePlan(escalation.getAlternativePlan())
                .outcome(escalation.getOutcome())
                .notes(escalation.getNotes())
                .createdAt(escalation.getCreatedAt());

        // Visit info
        if (escalation.getVisit() != null) {
            builder.visitId(escalation.getVisit().getId());
            builder.visitNumber(escalation.getVisit().getVisitNumber());
            builder.triageCategory(escalation.getVisit().getCurrentTriageCategory());
            if (escalation.getVisit().getPatient() != null) {
                builder.patientName(
                        escalation.getVisit().getPatient().getFirstName() + " " +
                                escalation.getVisit().getPatient().getLastName());
            }
        }

        return builder.build();
    }
}
