package com.smartTriage.smartTriage_server.module.referral.dto;

import com.smartTriage.smartTriage_server.common.enums.ReferralType;
import com.smartTriage.smartTriage_server.common.enums.TransportMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for initiating a new inter-hospital referral.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InitiateReferralRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotNull(message = "Referral type is required")
    private ReferralType referralType;

    @NotBlank(message = "Referring clinician name is required")
    private String referringClinician;

    private String referringClinicianPhone;

    @NotBlank(message = "Receiving hospital name is required")
    private String receivingHospitalName;

    private String receivingHospitalCode;

    @NotBlank(message = "Referral reason is required")
    private String referralReason;

    private String currentDiagnosis;
    private String interventionsGiven;
    private String ongoingTreatment;
    private TransportMode transportMode;
    private Boolean escortRequired;
    private String escortName;
    private String escortDesignation;
    private Integer estimatedTransferTimeMinutes;
    private String rhmisCaseNumber;
    private String samuRequestNumber;
    private String notes;
}
