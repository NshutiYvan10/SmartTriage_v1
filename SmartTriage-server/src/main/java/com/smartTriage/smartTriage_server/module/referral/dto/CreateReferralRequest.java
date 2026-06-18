package com.smartTriage.smartTriage_server.module.referral.dto;

import com.smartTriage.smartTriage_server.common.enums.ReferralType;
import com.smartTriage.smartTriage_server.common.enums.ReferralUrgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Raise a referral / consultation. The requester is derived from the
 *  authenticated user, never the request body. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReferralRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotNull(message = "Referral type is required")
    private ReferralType referralType;

    @NotBlank(message = "Target specialty / service is required")
    private String specialty;

    @NotNull(message = "Urgency is required")
    private ReferralUrgency urgency;

    @NotBlank(message = "Reason for referral is required")
    private String reasonForReferral;

    private String clinicalQuestion;
    private String targetFacility;
    private String notes;
}
