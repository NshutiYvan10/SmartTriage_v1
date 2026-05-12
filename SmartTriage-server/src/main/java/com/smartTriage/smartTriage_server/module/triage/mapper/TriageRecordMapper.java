package com.smartTriage.smartTriage_server.module.triage.mapper;

import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignEvent;
import com.smartTriage.smartTriage_server.module.clinicalsigns.service.ClinicalSignDefinitions;
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
        return toResponse(r, (ClinicalSignEvent) null);
    }

    /**
     * Phase G #2 overload — also populate the bed-suggestion fields on
     * the response. Pass {@code null} for {@code suggestedBed} when no
     * suggestion is available (zone full, category doesn't route to a
     * bed-bearing zone, or the engine wasn't invoked). Equivalent to
     * {@link #toResponse(TriageRecord)} with the suggestion fields left
     * at their default values.
     *
     * <p>Use this from the post-{@code performTriage} response path so
     * the form can show the nurse a "Place in suggested bed?" confirm.
     * History / getLatest paths do NOT re-run the suggestion engine —
     * they call the unary {@link #toResponse(TriageRecord)} overload.
     */
    public static TriageRecordResponse toResponse(TriageRecord r, Bed suggestedBed) {
        return toResponse(r, suggestedBed, false, null);
    }

    /**
     * Bed-aware overload used by the performTriage path when the
     * placement outcome must be returned alongside the suggestion.
     *
     * @param r            persisted triage record
     * @param suggestedBed the bed that was suggested (or auto-placed
     *                     into). May be null when no bed was available
     *                     and auto-placement was skipped.
     * @param autoPlaced   true when the placement happened in this
     *                     transaction (Option A flow). False when the
     *                     frontend must fall back to the modal.
     * @param note         human-readable note shown in the frontend
     *                     toast. Null when no message is needed.
     */
    public static TriageRecordResponse toResponse(
            TriageRecord r, Bed suggestedBed, boolean autoPlaced, String note) {
        TriageRecordResponse response = toResponse(r, (ClinicalSignEvent) null);
        if (suggestedBed != null) {
            response.setSuggestedBedId(suggestedBed.getId());
            response.setSuggestedBedCode(suggestedBed.getCode());
            response.setSuggestedBedZone(suggestedBed.getZone());
            response.setSuggestedBedHasMonitor(suggestedBed.isHasMonitor());
        }
        response.setAutoPlaced(autoPlaced);
        response.setAutoPlacementNote(note);
        return response;
    }

    /**
     * Round 3 hydration overload: when the caller can supply the
     * triggering ClinicalSignEvent (e.g. immediately after a system-
     * triggered re-triage where we just persisted both records), the
     * label / status / recordedAt fields populate without a follow-up
     * fetch. The unary overload is kept for the bulk/listing paths.
     */
    public static TriageRecordResponse toResponse(TriageRecord r, ClinicalSignEvent trigger) {
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

        // Round 3 — populate the system-triggered re-triage audit fields.
        // The id is on the entity always; the hydrated label / status / time
        // come from the supplied trigger event when available. Without it,
        // the frontend can render "System triggered" using just the id and
        // fetch full details on demand.
        if (r.getTriggeringSignEventId() != null) {
            b.triggeringSignEventId(r.getTriggeringSignEventId());
            if (trigger != null && trigger.getId().equals(r.getTriggeringSignEventId())) {
                b.triggeringSignCode(trigger.getSignCode())
                        .triggeringSignLabel(ClinicalSignDefinitions.labelOrCode(trigger.getSignCode()))
                        .triggeringSignStatus(trigger.getStatus())
                        .triggeringSignRecordedAt(trigger.getRecordedAt());
            }
        }

        return b.build();
    }
}
