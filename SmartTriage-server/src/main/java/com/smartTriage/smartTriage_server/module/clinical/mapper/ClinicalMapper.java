package com.smartTriage.smartTriage_server.module.clinical.mapper;

import com.smartTriage.smartTriage_server.module.clinical.dto.ClinicalNoteResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.DiagnosisResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.InvestigationResponse;
import com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote;
import com.smartTriage.smartTriage_server.module.clinical.entity.Diagnosis;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;

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
        return InvestigationResponse.builder()
                .id(investigation.getId())
                .visitId(investigation.getVisit().getId())
                .investigationType(investigation.getInvestigationType())
                .testName(investigation.getTestName())
                .orderedByName(investigation.getOrderedByName())
                .orderedAt(investigation.getOrderedAt())
                .specimenCollectedAt(investigation.getSpecimenCollectedAt())
                .resultedAt(investigation.getResultedAt())
                .result(investigation.getResult())
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
                .recordedAt(note.getRecordedAt())
                .section(note.getSection())
                .createdAt(note.getCreatedAt())
                .updatedAt(note.getUpdatedAt())
                .build();
    }
}
