package com.smartTriage.smartTriage_server.module.consent.dto;

import com.smartTriage.smartTriage_server.common.enums.ConsentGrantor;
import com.smartTriage.smartTriage_server.common.enums.DataSharingConsentStatus;
import com.smartTriage.smartTriage_server.common.enums.DataSharingScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSharingConsentResponse {

    private UUID id;
    private UUID personIdentityId;
    private DataSharingConsentStatus status;
    private DataSharingScope scope;
    private ConsentGrantor consentGrantor;
    private String grantorName;
    private String grantorRelationship;

    private String obtainedByName;
    private String obtainedByRole;
    private Instant obtainedAt;

    private String withdrawnByName;
    private Instant withdrawnAt;
    private String withdrawalReason;

    private String notes;
    private Instant createdAt;
}
