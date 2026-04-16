package com.smartTriage.smartTriage_server.module.referral.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording acceptance by the receiving facility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcceptReferralRequest {

    private String receivingClinician;
    private String receivingClinicianPhone;
    private String notes;
}
