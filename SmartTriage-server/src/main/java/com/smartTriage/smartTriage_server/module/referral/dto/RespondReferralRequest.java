package com.smartTriage.smartTriage_server.module.referral.dto;

import com.smartTriage.smartTriage_server.common.enums.ReferralStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The consultant's response to a referral. {@code outcome} must be ACCEPTED,
 * DECLINED or COMPLETED. The responder is the authenticated user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RespondReferralRequest {

    @NotNull(message = "Response outcome is required (ACCEPTED, DECLINED or COMPLETED)")
    private ReferralStatus outcome;

    /** The consultant's reply — assessment + recommendations. */
    private String responseNotes;

    /** Required when declining. */
    private String declineReason;
}
