package com.smartTriage.smartTriage_server.module.referral.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response DTO for a standardized referral summary document per Rwanda MoH format.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferralSummaryResponse {

    private UUID referralId;
    private String summaryDocument;
}
