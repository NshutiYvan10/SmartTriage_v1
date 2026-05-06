package com.smartTriage.smartTriage_server.module.visit.mapper;

import com.smartTriage.smartTriage_server.module.visit.dto.VisitResponse;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;

public final class VisitMapper {

    private VisitMapper() {}

    public static VisitResponse toResponse(Visit visit) {
        VisitResponse.VisitResponseBuilder b = VisitResponse.builder()
                .id(visit.getId())
                .visitNumber(visit.getVisitNumber())
                .patientId(visit.getPatient().getId())
                .patientName(visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName())
                .hospitalId(visit.getHospital().getId())
                .arrivalMode(visit.getArrivalMode())
                .arrivalTime(visit.getArrivalTime())
                .chiefComplaint(visit.getChiefComplaint())
                .status(visit.getStatus())
                .currentTriageCategory(visit.getCurrentTriageCategory())
                .currentTewsScore(visit.getCurrentTewsScore())
                .triageTime(visit.getTriageTime())
                .assessmentStartTime(visit.getAssessmentStartTime())
                .dispositionType(visit.getDispositionType())
                .dispositionTime(visit.getDispositionTime())
                .dispositionNotes(visit.getDispositionNotes())
                .referringFacility(visit.getReferringFacility())
                .isPediatric(visit.isPediatric())
                .retriageCount(visit.getRetriageCount())
                .currentEdZone(visit.getCurrentEdZone())
                .createdAt(visit.getCreatedAt())
                .updatedAt(visit.getUpdatedAt());
        if (visit.getPrimaryClinician() != null) {
            b.primaryClinicianId(visit.getPrimaryClinician().getId())
                    .primaryClinicianName(
                            visit.getPrimaryClinician().getFirstName() + " "
                                    + visit.getPrimaryClinician().getLastName());
        }
        return b.build();
    }
}
