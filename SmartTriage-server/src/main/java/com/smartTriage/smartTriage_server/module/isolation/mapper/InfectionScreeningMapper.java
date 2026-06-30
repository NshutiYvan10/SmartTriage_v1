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
                .immunocompromised(screening.isImmunocompromised())
                .hasNeckStiffness(screening.isHasNeckStiffness())
                .requiresN95(screening.isRequiresN95())
                .requiresGown(screening.isRequiresGown())
                .requiresGloves(screening.isRequiresGloves())
                .requiresFaceShield(screening.isRequiresFaceShield())
                .requiresApron(screening.isRequiresApron())
                .requiresBootCovers(screening.isRequiresBootCovers())
                .isolationRoomAssigned(screening.getIsolationRoomAssigned())
                .isolationRoomAssignedAt(screening.getIsolationRoomAssignedAt())
                .isolationAssignedByName(screening.getIsolationAssignedByName())
                .isolationStartedAt(screening.getIsolationStartedAt())
                .placementDueAt(screening.getPlacementDueAt())
                .isolationEndedAt(screening.getIsolationEndedAt())
                .isolationEndedByName(screening.getIsolationEndedByName())
                .isolationEndReason(screening.getIsolationEndReason())
                .publicHealthNotifiedAt(screening.getPublicHealthNotifiedAt())
                .publicHealthReferenceNumber(screening.getPublicHealthReferenceNumber())
                .publicHealthNotifiedByName(screening.getPublicHealthNotifiedByName())
                .notes(screening.getNotes())
                .findings(findings)
                .createdAt(screening.getCreatedAt());

        if (screening.getVisit() != null) {
            var visit = screening.getVisit();
            builder.visitId(visit.getId());
            builder.visitNumber(visit.getVisitNumber());
            if (visit.getPatient() != null) {
                builder.patientName(
                        visit.getPatient().getFirstName() + " " +
                                visit.getPatient().getLastName());
            }
            // Denormalise WHERE the patient is so the isolation dashboard row shows
            // zone + bed without a second fetch (was declared-but-never-set → always null).
            if (visit.getCurrentEdZone() != null) {
                builder.currentZone(visit.getCurrentEdZone().name());
            }
            if (visit.getCurrentBed() != null) {
                builder.currentBedLabel(visit.getCurrentBed().getCode());
            }
        }

        return builder.build();
    }
}
