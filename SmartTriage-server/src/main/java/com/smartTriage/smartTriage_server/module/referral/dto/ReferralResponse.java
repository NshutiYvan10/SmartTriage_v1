package com.smartTriage.smartTriage_server.module.referral.dto;

import com.smartTriage.smartTriage_server.common.enums.ReferralStatus;
import com.smartTriage.smartTriage_server.common.enums.ReferralType;
import com.smartTriage.smartTriage_server.common.enums.ReferralUrgency;
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
public class ReferralResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;

    private ReferralType referralType;
    private String specialty;
    private ReferralUrgency urgency;
    private String reasonForReferral;
    private String clinicalQuestion;
    private String targetFacility;
    private ReferralStatus status;

    private UUID requestedByUserId;
    private String requestedByName;
    private String requestedByRole;
    private Instant requestedAt;

    private UUID respondedByUserId;
    private String respondedByName;
    private String respondedByRole;
    private Instant respondedAt;
    private String responseNotes;
    private String declineReason;

    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
