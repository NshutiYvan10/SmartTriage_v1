package com.smartTriage.smartTriage_server.module.visit.mapper;

import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.service.UnidentifiedPatientNameService;
import com.smartTriage.smartTriage_server.module.visit.dto.VisitResponse;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;

public final class VisitMapper {

    private VisitMapper() {}

    public static VisitResponse toResponse(Visit visit) {
        VisitResponse.VisitResponseBuilder b = VisitResponse.builder()
                .id(visit.getId())
                .visitNumber(visit.getVisitNumber())
                .patientId(visit.getPatient().getId())
                .patientName(formatPatientName(visit))
                // Carry DOB + gender on the list payload so every view
                // (triage queue, patients list, monitoring, dashboard)
                // can render age/gender from the same source the visit
                // record uses, without an N+1 fetch per row.
                .patientDateOfBirth(visit.getPatient().getDateOfBirth())
                .patientGender(visit.getPatient().getGender())
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
                .dispositionDestinationWard(visit.getDispositionDestinationWard())
                .dispositionReceivingFacility(visit.getDispositionReceivingFacility())
                .referringFacility(visit.getReferringFacility())
                .isPediatric(visit.isPediatric())
                .retriageCount(visit.getRetriageCount())
                .currentEdZone(visit.getCurrentEdZone())
                // Direct Resus Admission flags (V44)
                .pendingResusOverflow(visit.isPendingResusOverflow())
                .ambulancePreArrival(visit.isAmbulancePreArrival())
                .arrivalConfirmedAt(visit.getArrivalConfirmedAt())
                .emsRunId(visit.getEmsRunId())
                .fieldTriageCategory(visit.getFieldTriageCategory())
                .edRetriageDueAt(visit.getEdRetriageDueAt())
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

    /**
     * Format the patient name for display. For unidentified patients
     * (Direct Resus placeholder), uses the phonetic display name
     * ("Unknown Alpha (child)") so the visit list and search results
     * read correctly in every UI surface without each surface having
     * to re-implement the formatting.
     */
    private static String formatPatientName(Visit visit) {
        Patient p = visit.getPatient();
        if (p == null) return "Unknown patient";
        if (p.isUnidentified()) {
            return UnidentifiedPatientNameService.buildDisplayName(
                    p.getPlaceholderLabel(), visit.isPediatric());
        }
        return ((p.getFirstName() != null ? p.getFirstName() : "") + " "
                + (p.getLastName() != null ? p.getLastName() : "")).trim();
    }
}
