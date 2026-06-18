package com.smartTriage.smartTriage_server.module.consent.dto;

import com.smartTriage.smartTriage_server.common.enums.ConsentGrantor;
import com.smartTriage.smartTriage_server.common.enums.ConsentStatus;
import com.smartTriage.smartTriage_server.common.enums.ConsentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsentResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;

    private ConsentType consentType;
    private String procedureName;
    private String description;

    private String risksExplained;
    private String benefitsExplained;
    private String alternativesExplained;
    private boolean questionsAnswered;
    private boolean interpreterUsed;
    private String interpreterName;
    private String language;

    private ConsentGrantor consentGrantor;
    private String grantorName;
    private String grantorRelationship;
    private String witnessName;

    private ConsentStatus status;
    private UUID obtainedByUserId;
    private String obtainedByName;
    private String obtainedByRole;
    private String obtainedByLicenseNumber;
    private Instant obtainedAt;

    private UUID withdrawnByUserId;
    private String withdrawnByName;
    private Instant withdrawnAt;
    private String withdrawalReason;

    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
