package com.smartTriage.smartTriage_server.module.consent.mapper;

import com.smartTriage.smartTriage_server.module.consent.dto.DataSharingConsentResponse;
import com.smartTriage.smartTriage_server.module.consent.entity.DataSharingConsent;

public final class DataSharingConsentMapper {

    private DataSharingConsentMapper() {
    }

    public static DataSharingConsentResponse toResponse(DataSharingConsent c) {
        return DataSharingConsentResponse.builder()
                .id(c.getId())
                .personIdentityId(c.getPersonIdentity() != null ? c.getPersonIdentity().getId() : null)
                .status(c.getStatus())
                .scope(c.getScope())
                .consentGrantor(c.getConsentGrantor())
                .grantorName(c.getGrantorName())
                .grantorRelationship(c.getGrantorRelationship())
                .obtainedByName(c.getObtainedByName())
                .obtainedByRole(c.getObtainedByRole())
                .obtainedAt(c.getObtainedAt())
                .withdrawnByName(c.getWithdrawnByName())
                .withdrawnAt(c.getWithdrawnAt())
                .withdrawalReason(c.getWithdrawalReason())
                .notes(c.getNotes())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
