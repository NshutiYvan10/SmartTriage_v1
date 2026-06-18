package com.smartTriage.smartTriage_server.module.referral.mapper;

import com.smartTriage.smartTriage_server.module.referral.dto.ReferralResponse;
import com.smartTriage.smartTriage_server.module.referral.entity.Referral;

public final class ReferralMapper {

    private ReferralMapper() {}

    public static ReferralResponse toResponse(Referral r) {
        return ReferralResponse.builder()
                .id(r.getId())
                .visitId(r.getVisit().getId())
                .visitNumber(r.getVisit().getVisitNumber())
                .referralType(r.getReferralType())
                .specialty(r.getSpecialty())
                .urgency(r.getUrgency())
                .reasonForReferral(r.getReasonForReferral())
                .clinicalQuestion(r.getClinicalQuestion())
                .targetFacility(r.getTargetFacility())
                .status(r.getStatus())
                .requestedByUserId(r.getRequestedByUserId())
                .requestedByName(r.getRequestedByName())
                .requestedByRole(r.getRequestedByRole())
                .requestedAt(r.getRequestedAt())
                .respondedByUserId(r.getRespondedByUserId())
                .respondedByName(r.getRespondedByName())
                .respondedByRole(r.getRespondedByRole())
                .respondedAt(r.getRespondedAt())
                .responseNotes(r.getResponseNotes())
                .declineReason(r.getDeclineReason())
                .notes(r.getNotes())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
