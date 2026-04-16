package com.smartTriage.smartTriage_server.module.safety.mapper;

import com.smartTriage.smartTriage_server.module.safety.dto.SafetyIncidentResponse;
import com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident;

/**
 * Mapper for SafetyIncident entity to response DTO.
 * Handles anonymous reporting by stripping reporter identity when isAnonymous is true.
 */
public final class SafetyIncidentMapper {

    private SafetyIncidentMapper() {
    }

    public static SafetyIncidentResponse toResponse(SafetyIncident incident) {
        SafetyIncidentResponse.SafetyIncidentResponseBuilder builder = SafetyIncidentResponse.builder()
                .id(incident.getId())
                .incidentNumber(incident.getIncidentNumber())
                .incidentType(incident.getIncidentType())
                .severity(incident.getSeverity())
                .status(incident.getStatus())
                .incidentDateTime(incident.getIncidentDateTime())
                .locationInHospital(incident.getLocationInHospital())
                .description(incident.getDescription())
                .contributingFactors(incident.getContributingFactors())
                .immediateActions(incident.getImmediateActions())
                .reportedAt(incident.getReportedAt())
                .patientHarmed(incident.getPatientHarmed())
                .investigatorName(incident.getInvestigatorName())
                .investigationStartedAt(incident.getInvestigationStartedAt())
                .rootCauseAnalysis(incident.getRootCauseAnalysis())
                .rootCauseCategory(incident.getRootCauseCategory())
                .investigationCompletedAt(incident.getInvestigationCompletedAt())
                .correctiveAction(incident.getCorrectiveAction())
                .correctiveActionOwner(incident.getCorrectiveActionOwner())
                .correctiveActionDeadline(incident.getCorrectiveActionDeadline())
                .correctiveActionCompletedAt(incident.getCorrectiveActionCompletedAt())
                .preventiveMeasures(incident.getPreventiveMeasures())
                .closedAt(incident.getClosedAt())
                .closedByName(incident.getClosedByName())
                .lessonsLearned(incident.getLessonsLearned())
                .isAnonymous(incident.isAnonymous())
                .notes(incident.getNotes())
                .createdAt(incident.getCreatedAt());

        // Strip reporter identity for anonymous reports
        if (incident.isAnonymous()) {
            builder.reportedByName("Anonymous");
            builder.reportedByRole(null);
            builder.involvedStaffNames(null);
        } else {
            builder.reportedByName(incident.getReportedByName());
            builder.reportedByRole(incident.getReportedByRole());
            builder.involvedStaffNames(incident.getInvolvedStaffNames());
        }

        if (incident.getHospital() != null) {
            builder.hospitalId(incident.getHospital().getId());
            builder.hospitalName(incident.getHospital().getName());
        }

        if (incident.getVisit() != null) {
            builder.visitId(incident.getVisit().getId());
            builder.visitNumber(incident.getVisit().getVisitNumber());
        }

        return builder.build();
    }
}
