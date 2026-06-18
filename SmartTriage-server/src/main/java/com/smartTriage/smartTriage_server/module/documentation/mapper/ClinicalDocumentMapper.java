package com.smartTriage.smartTriage_server.module.documentation.mapper;

import com.smartTriage.smartTriage_server.module.documentation.dto.ClinicalDocumentResponse;
import com.smartTriage.smartTriage_server.module.documentation.entity.ClinicalDocument;

/**
 * Maps ClinicalDocument entities to response DTOs.
 */
public final class ClinicalDocumentMapper {

    private ClinicalDocumentMapper() {}

    public static ClinicalDocumentResponse toResponse(ClinicalDocument doc) {
        return ClinicalDocumentResponse.builder()
                .id(doc.getId())
                .visitId(doc.getVisit().getId())
                .visitNumber(doc.getVisit().getVisitNumber())
                .documentType(doc.getDocumentType())
                .title(doc.getTitle())
                .content(doc.getContent())
                .authorUserId(doc.getAuthorUserId())
                .authorName(doc.getAuthorName())
                .authorRole(doc.getAuthorRole())
                .authorLicenseNumber(doc.getAuthorLicenseNumber())
                .signedAt(doc.getSignedAt())
                .isSigned(doc.isSigned())
                .coSignedByUserId(doc.getCoSignedByUserId())
                .coSignedByName(doc.getCoSignedByName())
                .coSignedByRole(doc.getCoSignedByRole())
                .coSignedByLicenseNumber(doc.getCoSignedByLicenseNumber())
                .coSignedAt(doc.getCoSignedAt())
                .vitalSignsId(doc.getVitalSigns() != null ? doc.getVitalSigns().getId() : null)
                .isAmendment(doc.isAmendment())
                .amendmentReason(doc.getAmendmentReason())
                .originalDocumentId(doc.getOriginalDocument() != null ? doc.getOriginalDocument().getId() : null)
                .amendedAt(doc.getAmendedAt())
                .templateUsed(doc.getTemplateUsed())
                .notes(doc.getNotes())
                .procedurePerformed(doc.getProcedurePerformed())
                .procedureIndication(doc.getProcedureIndication())
                .procedureFindings(doc.getProcedureFindings())
                .procedureComplications(doc.getProcedureComplications())
                .procedureOutcome(doc.getProcedureOutcome())
                .procedurePerformedBy(doc.getProcedurePerformedBy())
                .anaesthesiaType(doc.getAnaesthesiaType())
                .timeOfDeath(doc.getTimeOfDeath())
                .causeOfDeath(doc.getCauseOfDeath())
                .antecedentCauses(doc.getAntecedentCauses())
                .mannerOfDeath(doc.getMannerOfDeath())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
