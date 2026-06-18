package com.smartTriage.smartTriage_server.module.consent.mapper;

import com.smartTriage.smartTriage_server.module.consent.dto.ConsentResponse;
import com.smartTriage.smartTriage_server.module.consent.entity.InformedConsent;

public final class ConsentMapper {

    private ConsentMapper() {}

    public static ConsentResponse toResponse(InformedConsent c) {
        return ConsentResponse.builder()
                .id(c.getId())
                .visitId(c.getVisit().getId())
                .visitNumber(c.getVisit().getVisitNumber())
                .consentType(c.getConsentType())
                .procedureName(c.getProcedureName())
                .description(c.getDescription())
                .risksExplained(c.getRisksExplained())
                .benefitsExplained(c.getBenefitsExplained())
                .alternativesExplained(c.getAlternativesExplained())
                .questionsAnswered(c.isQuestionsAnswered())
                .interpreterUsed(c.isInterpreterUsed())
                .interpreterName(c.getInterpreterName())
                .language(c.getLanguage())
                .consentGrantor(c.getConsentGrantor())
                .grantorName(c.getGrantorName())
                .grantorRelationship(c.getGrantorRelationship())
                .witnessName(c.getWitnessName())
                .status(c.getStatus())
                .obtainedByUserId(c.getObtainedByUserId())
                .obtainedByName(c.getObtainedByName())
                .obtainedByRole(c.getObtainedByRole())
                .obtainedByLicenseNumber(c.getObtainedByLicenseNumber())
                .obtainedAt(c.getObtainedAt())
                .withdrawnByUserId(c.getWithdrawnByUserId())
                .withdrawnByName(c.getWithdrawnByName())
                .withdrawnAt(c.getWithdrawnAt())
                .withdrawalReason(c.getWithdrawalReason())
                .notes(c.getNotes())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
