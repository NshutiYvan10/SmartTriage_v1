package com.smartTriage.smartTriage_server.module.clinical.mapper;

import com.smartTriage.smartTriage_server.module.clinical.dto.ClinicalNoteResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.DiagnosisResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.InvestigationResponse;
import com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote;
import com.smartTriage.smartTriage_server.module.clinical.entity.Diagnosis;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import com.smartTriage.smartTriage_server.common.enums.EdZone;

/**
 * Maps clinical entities to response DTOs.
 */
public final class ClinicalMapper {

    private ClinicalMapper() {}

    public static DiagnosisResponse toResponse(Diagnosis diagnosis) {
        return DiagnosisResponse.builder()
                .id(diagnosis.getId())
                .visitId(diagnosis.getVisit().getId())
                .diagnosisType(diagnosis.getDiagnosisType())
                .icdCode(diagnosis.getIcdCode())
                .description(diagnosis.getDescription())
                .diagnosedByName(diagnosis.getDiagnosedByName())
                .diagnosedAt(diagnosis.getDiagnosedAt())
                .isPrimary(diagnosis.getIsPrimary())
                .notes(diagnosis.getNotes())
                .createdAt(diagnosis.getCreatedAt())
                .updatedAt(diagnosis.getUpdatedAt())
                .build();
    }

    public static InvestigationResponse toResponse(Investigation investigation) {
        // Hydrate visit context for the doctor's aggregate "My Investigations"
        // view so it can render the visit number + patient name without
        // a second round-trip per row. Defensive nulls — Visit/Patient
        // shouldn't be null in practice but never NPE the mapper.
        String visitNumber = null;
        String patientName = null;
        EdZone currentZone = null;
        String currentBedLabel = null;
        if (investigation.getVisit() != null) {
            visitNumber = investigation.getVisit().getVisitNumber();
            // WHERE the patient/specimen is now — denormalised so the
            // doctor's aggregate "My Investigations" row can show location
            // without a second fetch. currentBed is nullable (unplaced).
            currentZone = investigation.getVisit().getCurrentEdZone();
            if (investigation.getVisit().getCurrentBed() != null) {
                currentBedLabel = investigation.getVisit().getCurrentBed().getCode();
            }
            if (investigation.getVisit().getPatient() != null) {
                String fn = investigation.getVisit().getPatient().getFirstName();
                String ln = investigation.getVisit().getPatient().getLastName();
                String composed = ((fn != null ? fn : "") + " " + (ln != null ? ln : "")).trim();
                patientName = composed.isEmpty() ? null : composed;
            }
        }
        return InvestigationResponse.builder()
                .id(investigation.getId())
                .visitId(investigation.getVisit().getId())
                .visitNumber(visitNumber)
                .patientName(patientName)
                .currentZone(currentZone)
                .currentBedLabel(currentBedLabel)
                .investigationType(investigation.getInvestigationType())
                .labRouted(investigation.getInvestigationType() != null
                        && investigation.getInvestigationType().isLabRoutable())
                .testName(investigation.getTestName())
                .orderedById(investigation.getOrderedBy() != null
                        ? investigation.getOrderedBy().getId() : null)
                .orderedByName(investigation.getOrderedByName())
                .orderedAt(investigation.getOrderedAt())
                .specimenCollectedAt(investigation.getSpecimenCollectedAt())
                .resultedAt(investigation.getResultedAt())
                .result(investigation.getResult())
                .resultNumeric(investigation.getResultNumeric())
                .resultUnit(investigation.getResultUnit())
                .isAbnormal(investigation.getIsAbnormal())
                .isCritical(investigation.getIsCritical())
                .status(investigation.getStatus())
                .priority(investigation.getPriority())
                .notes(investigation.getNotes())
                .createdAt(investigation.getCreatedAt())
                .updatedAt(investigation.getUpdatedAt())
                .build();
    }

    public static ClinicalNoteResponse toResponse(ClinicalNote note) {
        return ClinicalNoteResponse.builder()
                .id(note.getId())
                .visitId(note.getVisit().getId())
                .noteType(note.getNoteType())
                .content(note.getContent())
                .recordedByName(note.getRecordedByName())
                .authorUserId(note.getAuthorUserId())
                .authorRole(note.getAuthorRole())
                .supersedesId(note.getSupersedesId())
                .recordedAt(note.getRecordedAt())
                .section(note.getSection())
                .createdAt(note.getCreatedAt())
                .updatedAt(note.getUpdatedAt())
                .build();
    }
}
