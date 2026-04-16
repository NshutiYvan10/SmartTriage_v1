package com.smartTriage.smartTriage_server.module.referral.mapper;

import com.smartTriage.smartTriage_server.module.referral.dto.ReferralResponse;
import com.smartTriage.smartTriage_server.module.referral.entity.Referral;

/**
 * Mapper for Referral entity to response DTO.
 */
public final class ReferralMapper {

    private ReferralMapper() {
    }

    public static ReferralResponse toResponse(Referral referral) {
        ReferralResponse.ReferralResponseBuilder builder = ReferralResponse.builder()
                .id(referral.getId())
                .referralType(referral.getReferralType())
                .status(referral.getStatus())
                .referringClinician(referral.getReferringClinician())
                .referringClinicianPhone(referral.getReferringClinicianPhone())
                .receivingHospitalName(referral.getReceivingHospitalName())
                .receivingHospitalCode(referral.getReceivingHospitalCode())
                .receivingClinician(referral.getReceivingClinician())
                .receivingClinicianPhone(referral.getReceivingClinicianPhone())
                .referralReason(referral.getReferralReason())
                .clinicalSummary(referral.getClinicalSummary())
                .currentDiagnosis(referral.getCurrentDiagnosis())
                .currentTriageCategory(referral.getCurrentTriageCategory())
                .currentTewsScore(referral.getCurrentTewsScore())
                .interventionsGiven(referral.getInterventionsGiven())
                .ongoingTreatment(referral.getOngoingTreatment())
                .airwaySecured(referral.getAirwaySecured())
                .breathingStable(referral.getBreathingStable())
                .circulationStable(referral.getCirculationStable())
                .ivAccessEstablished(referral.getIvAccessEstablished())
                .medicationsDocumented(referral.getMedicationsDocumented())
                .allergiesDocumented(referral.getAllergiesDocumented())
                .bloodTypeDocumented(referral.getBloodTypeDocumented())
                .consentObtained(referral.getConsentObtained())
                .referralFormCompleted(referral.getReferralFormCompleted())
                .patientIdBandApplied(referral.getPatientIdBandApplied())
                .transportMode(referral.getTransportMode())
                .escortRequired(referral.getEscortRequired())
                .escortName(referral.getEscortName())
                .escortDesignation(referral.getEscortDesignation())
                .estimatedTransferTimeMinutes(referral.getEstimatedTransferTimeMinutes())
                .departedAt(referral.getDepartedAt())
                .arrivedAt(referral.getArrivedAt())
                .actualTransferTimeMinutes(referral.getActualTransferTimeMinutes())
                .initiatedAt(referral.getInitiatedAt())
                .receivingContactedAt(referral.getReceivingContactedAt())
                .acceptedAt(referral.getAcceptedAt())
                .stabilizedAt(referral.getStabilizedAt())
                .completedAt(referral.getCompletedAt())
                .rhmisCaseNumber(referral.getRhmisCaseNumber())
                .samuRequestNumber(referral.getSamuRequestNumber())
                .notes(referral.getNotes())
                .createdAt(referral.getCreatedAt());

        if (referral.getVisit() != null) {
            builder.visitId(referral.getVisit().getId());
            builder.visitNumber(referral.getVisit().getVisitNumber());
            if (referral.getVisit().getPatient() != null) {
                builder.patientName(
                        referral.getVisit().getPatient().getFirstName() + " " +
                                referral.getVisit().getPatient().getLastName());
            }
        }

        if (referral.getReferringHospital() != null) {
            builder.referringHospitalId(referral.getReferringHospital().getId());
            builder.referringHospitalName(referral.getReferringHospital().getName());
        }

        return builder.build();
    }
}
