package com.smartTriage.smartTriage_server.module.pathway.mapper;

import com.smartTriage.smartTriage_server.module.pathway.dto.*;
import com.smartTriage.smartTriage_server.module.pathway.entity.*;

/**
 * Maps pathway entities to response DTOs.
 */
public final class PathwayMapper {

    private PathwayMapper() {}

    public static ClinicalPathwayResponse toResponse(ClinicalPathway pathway) {
        return ClinicalPathwayResponse.builder()
                .id(pathway.getId())
                .pathwayCode(pathway.getPathwayCode())
                .pathwayName(pathway.getPathwayName())
                .category(pathway.getCategory())
                .description(pathway.getDescription())
                .targetPopulation(pathway.getTargetPopulation())
                .protocolVersion(pathway.getProtocolVersion())
                .sourceGuideline(pathway.getSourceGuideline())
                .isActive(pathway.isActive())
                .createdAt(pathway.getCreatedAt())
                .build();
    }

    public static PathwayStepResponse toResponse(PathwayStep step) {
        return PathwayStepResponse.builder()
                .id(step.getId())
                .pathwayId(step.getPathway().getId())
                .stepOrder(step.getStepOrder())
                .stepTitle(step.getStepTitle())
                .stepDescription(step.getStepDescription())
                .timeframeMinutes(step.getTimeframeMinutes())
                .isMandatory(step.isMandatory())
                .category(step.getCategory())
                .build();
    }

    public static PathwayActivationResponse toResponse(PathwayActivation activation) {
        return PathwayActivationResponse.builder()
                .id(activation.getId())
                .visitId(activation.getVisit().getId())
                .visitNumber(activation.getVisit().getVisitNumber())
                .pathwayId(activation.getPathway().getId())
                .pathwayName(activation.getPathway().getPathwayName())
                .pathwayCode(activation.getPathway().getPathwayCode())
                .activatedAt(activation.getActivatedAt())
                .activatedByName(activation.getActivatedByName())
                .completedAt(activation.getCompletedAt())
                .status(activation.getStatus())
                .deviationReason(activation.getDeviationReason())
                .notes(activation.getNotes())
                .createdAt(activation.getCreatedAt())
                .build();
    }

    public static PathwayStepCompletionResponse toResponse(PathwayStepCompletion completion) {
        return PathwayStepCompletionResponse.builder()
                .id(completion.getId())
                .activationId(completion.getActivation().getId())
                .stepId(completion.getStep().getId())
                .stepTitle(completion.getStep().getStepTitle())
                .stepOrder(completion.getStep().getStepOrder())
                .completedAt(completion.getCompletedAt())
                .completedByName(completion.getCompletedByName())
                .wasSkipped(completion.isWasSkipped())
                .skipReason(completion.getSkipReason())
                .notes(completion.getNotes())
                .timeToCompleteMinutes(completion.getTimeToCompleteMinutes())
                .build();
    }
}
