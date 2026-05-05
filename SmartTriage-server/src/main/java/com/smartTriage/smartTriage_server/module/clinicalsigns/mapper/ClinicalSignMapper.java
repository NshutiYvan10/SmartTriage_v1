package com.smartTriage.smartTriage_server.module.clinicalsigns.mapper;

import com.smartTriage.smartTriage_server.module.clinicalsigns.dto.ClinicalSignEventResponse;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignEvent;

public final class ClinicalSignMapper {

    private ClinicalSignMapper() {}

    public static ClinicalSignEventResponse toResponse(ClinicalSignEvent e) {
        return ClinicalSignEventResponse.builder()
                .id(e.getId())
                .visitId(e.getVisit() != null ? e.getVisit().getId() : null)
                .patientId(e.getPatient() != null ? e.getPatient().getId() : null)
                .signCode(e.getSignCode())
                .signCategory(e.getSignCategory())
                .status(e.getStatus())
                .numericValue(e.getNumericValue())
                .notes(e.getNotes())
                .recordedAt(e.getRecordedAt())
                .recordedById(e.getRecordedBy() != null ? e.getRecordedBy().getId() : null)
                .recordedByName(e.getRecordedByName())
                .isBaseline(e.isBaseline())
                .build();
    }
}
