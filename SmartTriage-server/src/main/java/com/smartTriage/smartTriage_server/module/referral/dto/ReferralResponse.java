package com.smartTriage.smartTriage_server.module.referral.dto;

import com.smartTriage.smartTriage_server.common.enums.ReferralStatus;
import com.smartTriage.smartTriage_server.common.enums.ReferralType;
import com.smartTriage.smartTriage_server.common.enums.TransportMode;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for referral data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferralResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;
    private String patientName;

    private ReferralType referralType;
    private ReferralStatus status;

    // Referring facility
    private UUID referringHospitalId;
    private String referringHospitalName;
    private String referringClinician;
    private String referringClinicianPhone;

    // Receiving facility
    private String receivingHospitalName;
    private String receivingHospitalCode;
    private String receivingClinician;
    private String receivingClinicianPhone;

    // Clinical details
    private String referralReason;
    private String clinicalSummary;
    private String currentDiagnosis;
    private TriageCategory currentTriageCategory;
    private Integer currentTewsScore;
    private String interventionsGiven;
    private String ongoingTreatment;

    // Stabilization checklist
    private Boolean airwaySecured;
    private Boolean breathingStable;
    private Boolean circulationStable;
    private Boolean ivAccessEstablished;
    private Boolean medicationsDocumented;
    private Boolean allergiesDocumented;
    private Boolean bloodTypeDocumented;
    private Boolean consentObtained;
    private Boolean referralFormCompleted;
    private Boolean patientIdBandApplied;

    // Transfer logistics
    private TransportMode transportMode;
    private Boolean escortRequired;
    private String escortName;
    private String escortDesignation;
    private Integer estimatedTransferTimeMinutes;
    private Instant departedAt;
    private Instant arrivedAt;
    private Integer actualTransferTimeMinutes;

    // Timestamps
    private Instant initiatedAt;
    private Instant receivingContactedAt;
    private Instant acceptedAt;
    private Instant stabilizedAt;
    private Instant completedAt;

    // Rwanda RHMIS
    private String rhmisCaseNumber;
    private String samuRequestNumber;

    private String notes;
    private Instant createdAt;
}
