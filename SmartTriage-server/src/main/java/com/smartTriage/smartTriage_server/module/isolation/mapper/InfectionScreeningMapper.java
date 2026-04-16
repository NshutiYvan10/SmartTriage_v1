package com.smartTriage.smartTriage_server.module.isolation.mapper;

import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningResponse;
import com.smartTriage.smartTriage_server.module.isolation.entity.InfectionScreening;

import java.util.List;

/**
 * Mapper for InfectionScreening entity to response DTO.
 */
public final class InfectionScreeningMapper {

    private InfectionScreeningMapper() {
    }

    public static InfectionScreeningResponse toResponse(InfectionScreening screening) {
        return toResponse(screening, null);
    }

    public static InfectionScreeningResponse toResponse(InfectionScreening screening, List<String> findings) {
        InfectionScreeningResponse.InfectionScreeningResponseBuilder builder = InfectionScreeningResponse.builder()
                .id(screening.getId())
                .screenedAt(screening.getScreenedAt())
                .screenedByName(screening.getScreenedByName())
                .riskLevel(screening.getRiskLevel())
                .isolationType(screening.getIsolationType())
                .suspectedCondition(screening.getSuspectedCondition())
                .notifiableDisease(screening.getNotifiableDisease())
                .hasFever(screening.isHasFever())
                .hasCough(screening.isHasCough())
                .hasCoughDurationWeeks(screening.getHasCoughDurationWeeks())
                .hasNightSweats(screening.isHasNightSweats())
                .hasWeightLoss(screening.isHasWeightLoss())
                .hasRash(screening.isHasRash())
                .hasDiarrhea(screening.isHasDiarrhea())
                .hasRecentTravel(screening.isHasRecentTravel())
                .recentTravelLocation(screening.getRecentTravelLocation())
                .hasContactWithInfectious(screening.isHasContactWithInfectious())
                .contactDetails(screening.getContactDetails())
                .hasBleedingSymptoms(screening.isHasBleedingSymptoms())
                .isHealthcareWorker(screening.isHealthcareWorker())
                .requiresN95(screening.isRequiresN95())
                .requiresGown(screening.isRequiresGown())
                .requiresGloves(screening.isRequiresGloves())
                .requiresFaceShield(screening.isRequiresFaceShield())
                .requiresApron(screening.isRequiresApron())
                .requiresBootCovers(screening.isRequiresBootCovers())
                .isolationRoomAssigned(screening.getIsolationRoomAssigned())
                .isolationStartedAt(screening.getIsolationStartedAt())
                .isolationEndedAt(screening.getIsolationEndedAt())
                .publicHealthNotifiedAt(screening.getPublicHealthNotifiedAt())
                .publicHealthReferenceNumber(screening.getPublicHealthReferenceNumber())
                .notes(screening.getNotes())
                .findings(findings)
                .createdAt(screening.getCreatedAt());

        if (screening.getVisit() != null) {
            builder.visitId(screening.getVisit().getId());
            builder.visitNumber(screening.getVisit().getVisitNumber());
            if (screening.getVisit().getPatient() != null) {
                builder.patientName(
                        screening.getVisit().getPatient().getFirstName() + " " +
                                screening.getVisit().getPatient().getLastName());
            }
        }

        return builder.build();
    }
}
