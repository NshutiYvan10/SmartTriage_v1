package com.smartTriage.smartTriage_server.module.triage.mapper;

import com.smartTriage.smartTriage_server.module.triage.dto.TriageRecordResponse;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;

/**
 * Maps TriageRecord entity to TriageRecordResponse DTO.
 * Covers all fields from both the Rwanda National Standard Adult and Child
 * Triage Forms.
 */
public final class TriageRecordMapper {

    private TriageRecordMapper() {
    }

    public static TriageRecordResponse toResponse(TriageRecord r) {
        TriageRecordResponse.TriageRecordResponseBuilder b = TriageRecordResponse.builder()
                .id(r.getId())
                .visitId(r.getVisit().getId())
                .triageTime(r.getTriageTime())

                // Emergency Signs
                .hasAirwayCompromise(r.isHasAirwayCompromise())
                .hasBreathingDistress(r.isHasBreathingDistress())
                .hasSevereRespiratoryDistress(r.isHasSevereRespiratoryDistress())
                .hasCardiacArrest(r.isHasCardiacArrest())
                .hasUncontrolledHaemorrhage(r.isHasUncontrolledHaemorrhage())
                .hasStabGunWoundNeckChest(r.isHasStabGunWoundNeckChest())
                .hasConvulsions(r.isHasConvulsions())
                .convulsionGlucose(r.getConvulsionGlucose())
                .hasComa(r.isHasComa())
                .comaGlucose(r.getComaGlucose())
                .hasHypoglycaemia(r.isHasHypoglycaemia())
                .hasPurpuricRash(r.isHasPurpuricRash())
                .hasBurnFaceInhalation(r.isHasBurnFaceInhalation())

                // Child-Specific Emergency Signs
                .isChildForm(r.isChildForm())
                .childCentralCyanosis(r.isChildCentralCyanosis())
                .childPulseLowOrAbsent(r.isChildPulseLowOrAbsent())
                .childColdHandsComposite(r.isChildColdHandsComposite())
                .childColdHandsLethargic(r.isChildColdHandsLethargic())
                .childColdHandsPulseWeakFast(r.isChildColdHandsPulseWeakFast())
                .childColdHandsCapRefill(r.isChildColdHandsCapRefill())
                .childSevereDehydration(r.isChildSevereDehydration())
                .childDehydrationSkinPinch(r.isChildDehydrationSkinPinch())
                .childDehydrationLethargy(r.isChildDehydrationLethargy())
                .childDehydrationSunkenEyes(r.isChildDehydrationSunkenEyes())
                .childWeightKg(r.getChildWeightKg())
                .childHeightCm(r.getChildHeightCm())

                // Additional Vitals
                .spo2(r.getSpo2())
                .diastolicBp(r.getDiastolicBp())
                .bloodGlucose(r.getBloodGlucose())
                .painScore(r.getPainScore())
                .weightKg(r.getWeightKg())
                .heightCm(r.getHeightCm())

                // TEWS components
                .mobility(r.getMobility())
                .avpu(r.getAvpu())
                .traumaStatus(r.getTraumaStatus())

                // Very Urgent Signs — Medical
                .vuFocalNeurologicDeficit(r.isVuFocalNeurologicDeficit())
                .vuAlteredMentalStatus(r.isVuAlteredMentalStatus())
                .vuNeurologicalGlucose(r.getVuNeurologicalGlucose())
                .vuChestPain(r.isVuChestPain())
                .vuPoisoningOverdose(r.isVuPoisoningOverdose())
                .vuPregnantAbdominalPain(r.isVuPregnantAbdominalPain())
                .vuCoughingVomitingBlood(r.isVuCoughingVomitingBlood())
                .vuDiabeticHighGlucose(r.isVuDiabeticHighGlucose())
                .vuDiabeticGlucose(r.getVuDiabeticGlucose())
                .vuAggression(r.isVuAggression())
                .vuShortnessOfBreath(r.isVuShortnessOfBreath())

                // Very Urgent Signs — Trauma
                .vuBurnOver20Percent(r.isVuBurnOver20Percent())
                .vuOpenFracture(r.isVuOpenFracture())
                .vuThreatenedLimb(r.isVuThreatenedLimb())
                .vuEyeInjury(r.isVuEyeInjury())
                .vuLargeJointDislocation(r.isVuLargeJointDislocation())
                .vuSevereMechanismOfInjury(r.isVuSevereMechanismOfInjury())
                .vuVerySeverePain(r.isVuVerySeverePain())
                .vuPregnantAbdominalTrauma(r.isVuPregnantAbdominalTrauma())

                // Urgent Signs
                .urgUnableToDrinkVomits(r.isUrgUnableToDrinkVomits())
                .urgAbdominalPain(r.isUrgAbdominalPain())
                .urgVeryPale(r.isUrgVeryPale())
                .urgPregnantVaginalBleeding(r.isUrgPregnantVaginalBleeding())
                .urgDiabeticVeryHighGlucose(r.isUrgDiabeticVeryHighGlucose())
                .urgDiabeticGlucose(r.getUrgDiabeticGlucose())
                .urgFingerToeDislocation(r.isUrgFingerToeDislocation())
                .urgClosedFracture(r.isUrgClosedFracture())
                .urgBurnWithoutUrgentSigns(r.isUrgBurnWithoutUrgentSigns())
                .urgPregnantTraumaNonAbdominal(r.isUrgPregnantTraumaNonAbdominal())
                .urgModeratePain(r.isUrgModeratePain())
                .urgLacerationAbscess(r.isUrgLacerationAbscess())
                .urgForeignBodyAspiration(r.isUrgForeignBodyAspiration())

                // Computed results
                .tewsScore(r.getTewsScore())
                .triageCategory(r.getTriageCategory())
                .decisionPath(r.getDecisionPath())

                // Metadata
                .isRetriage(r.isRetriage())
                .isSystemTriggered(r.isSystemTriggered())
                .previousCategory(r.getPreviousCategory())
                .presentingComplaints(r.getPresentingComplaints())
                .clinicalNotes(r.getClinicalNotes())

                // Special Considerations
                .specialAcuteTrauma(r.isSpecialAcuteTrauma())
                .specialSeizureHistory(r.isSpecialSeizureHistory())
                .specialAssaultAbuse(r.isSpecialAssaultAbuse())
                .specialSuicideAttempt(r.isSpecialSuicideAttempt())

                // Triage Form Footer
                .triageNurseName(r.getTriageNurseName())
                .notifiedDoctorName(r.getNotifiedDoctorName())
                .doctorNotifiedAt(r.getDoctorNotifiedAt())
                .attendingDoctorName(r.getAttendingDoctorName())
                .doctorAttendedAt(r.getDoctorAttendedAt())

                .createdAt(r.getCreatedAt());

        if (r.getTriagedBy() != null) {
            b.triagedById(r.getTriagedBy().getId())
                    .triagedByName(r.getTriagedBy().getFirstName() + " " + r.getTriagedBy().getLastName());
        }

        if (r.getVitalSigns() != null) {
            b.vitalSignsId(r.getVitalSigns().getId());
        }

        return b.build();
    }
}
