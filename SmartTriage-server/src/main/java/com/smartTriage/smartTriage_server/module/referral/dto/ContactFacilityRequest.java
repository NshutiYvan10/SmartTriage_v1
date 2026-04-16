package com.smartTriage.smartTriage_server.module.referral.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording that a receiving facility was contacted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactFacilityRequest {

    private String receivingClinician;
    private String receivingClinicianPhone;
    private String notes;
}
