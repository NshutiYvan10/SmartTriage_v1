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

        // Visit info. isInitialized guards are defense-in-depth against the
        // lazy-mapping 500 (service returns a detached entity, this mapper
        // runs after the tx closed): the service hydrates every return path,
        // but a future call site that forgets must degrade to null fields —
        // never take down a mutation response whose write already committed.
        var visit = escalation.getVisit();
        if (visit != null && org.hibernate.Hibernate.isInitialized(visit)) {
            builder.visitId(visit.getId());
            builder.visitNumber(visit.getVisitNumber());
            builder.triageCategory(visit.getCurrentTriageCategory());
            // Patient CURRENT physical location (distinct from ICU destination bed).
            builder.currentEdZone(visit.getCurrentEdZone());
            if (visit.getCurrentBed() != null
                    && org.hibernate.Hibernate.isInitialized(visit.getCurrentBed())) {
                builder.currentBed(visit.getCurrentBed().getCode());
            }
            if (visit.getPatient() != null
                    && org.hibernate.Hibernate.isInitialized(visit.getPatient())) {
                builder.patientName(
                        visit.getPatient().getFirstName() + " " +
                                visit.getPatient().getLastName());
            }
        }

        return builder.build();
    }
}
