package com.smartTriage.smartTriage_server.module.fasttrack.mapper;

import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackResponse;
import com.smartTriage.smartTriage_server.module.fasttrack.entity.FastTrackActivation;

/**
 * Mapper for FastTrackActivation entity to response DTO.
 */
public final class FastTrackMapper {

    private FastTrackMapper() {
    }

    public static FastTrackResponse toResponse(FastTrackActivation activation) {
        FastTrackResponse.FastTrackResponseBuilder builder = FastTrackResponse.builder()
                .id(activation.getId())
                .fastTrackType(activation.getFastTrackType())
                .status(activation.getStatus())
                .activatedAt(activation.getActivatedAt())
                .activatedByName(activation.getActivatedByName())
                .symptomOnsetTime(activation.getSymptomOnsetTime())
                .beFastScore(activation.getBeFastScore())
                .nihssScore(activation.getNihssScore())
                .ctOrderedAt(activation.getCtOrderedAt())
                .ctCompletedAt(activation.getCtCompletedAt())
                .ctResult(activation.getCtResult())
                .isHemorrhagic(activation.getIsHemorrhagic())
                .thrombolysisEligible(activation.getThrombolysisEligible())
                .thrombolysisStartedAt(activation.getThrombolysisStartedAt())
                .doorToCtMinutes(activation.getDoorToCtMinutes())
                .chestPainOnsetTime(activation.getChestPainOnsetTime())
                .ecgOrderedAt(activation.getEcgOrderedAt())
                .ecgCompletedAt(activation.getEcgCompletedAt())
                .ecgResult(activation.getEcgResult())
                .stElevation(activation.getStElevation())
                .troponinOrdered(activation.getTroponinOrdered())
                .troponinResult(activation.getTroponinResult())
                .troponinResultedAt(activation.getTroponinResultedAt())
                .aspirinGiven(activation.getAspirinGiven())
                .aspirinGivenAt(activation.getAspirinGivenAt())
                .anticoagulantGiven(activation.getAnticoagulantGiven())
                .referredForPci(activation.getReferredForPci())
                .referredForPciAt(activation.getReferredForPciAt())
                .doorToEcgMinutes(activation.getDoorToEcgMinutes())
                .doorToNeedleMinutes(activation.getDoorToNeedleMinutes())
                .completedAt(activation.getCompletedAt())
                .outcome(activation.getOutcome())
                .notes(activation.getNotes())
                .createdAt(activation.getCreatedAt());

        if (activation.getVisit() != null) {
            builder.visitId(activation.getVisit().getId());
            builder.visitNumber(activation.getVisit().getVisitNumber());
            if (activation.getVisit().getPatient() != null) {
                builder.patientName(
                        activation.getVisit().getPatient().getFirstName() + " " +
                                activation.getVisit().getPatient().getLastName());
            }
        }

        return builder.build();
    }
}
