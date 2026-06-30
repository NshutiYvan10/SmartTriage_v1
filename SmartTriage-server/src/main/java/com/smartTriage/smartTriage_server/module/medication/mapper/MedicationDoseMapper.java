package com.smartTriage.smartTriage_server.module.medication.mapper;

import com.smartTriage.smartTriage_server.module.medication.dto.MedicationDoseResponse;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;

/**
 * Maps MedicationDose entities to response DTOs (V67).
 *
 * <p>Callers must ensure the dose's {@code medication} and {@code
 * visit} (+ patient) associations are initialised — board/audit
 * queries use JOIN FETCH for exactly this reason.
 */
public final class MedicationDoseMapper {

    private MedicationDoseMapper() {}

    public static MedicationDoseResponse toResponse(MedicationDose dose) {
        MedicationAdministration med = dose.getMedication();
        Visit visit = dose.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;

        return MedicationDoseResponse.builder()
                .id(dose.getId())
                .medicationId(med != null ? med.getId() : null)
                .visitId(visit != null ? visit.getId() : null)
                .kind(dose.getKind())
                .status(dose.getStatus())
                .sequenceNumber(dose.getSequenceNumber())
                .dueAt(dose.getDueAt())
                .givenAt(dose.getGivenAt())
                .givenById(dose.getGivenBy() != null ? dose.getGivenBy().getId() : null)
                .givenByName(dose.getGivenByName())
                .witnessName(dose.getWitnessName())
                .doseValue(dose.getDoseValue())
                .doseUnit(dose.getDoseUnit())
                .rateValue(dose.getRateValue())
                .rateUnit(dose.getRateUnit())
                .prnReason(dose.getPrnReason())
                .gateEvaluation(dose.getGateEvaluation())
                .isOverride(dose.isOverride())
                .overrideJustification(dose.getOverrideJustification())
                .statusReason(dose.getStatusReason())
                .delayCount(dose.getDelayCount())
                .drugName(med != null ? med.getDrugName() : null)
                .orderDose(med != null ? med.getDose() : null)
                .route(med != null ? med.getRoute() : null)
                .priority(med != null ? med.getPriority() : null)
                .prescriptionType(med != null ? med.getPrescriptionType() : null)
                .productType(med != null ? med.getProductType() : null)
                .productDetail(med != null ? med.getProductDetail() : null)
                .requiresWitness(med != null && med.isRequiresWitness())
                .prescribedByName(med != null ? med.getPrescribedByName() : null)
                .patientName(patient != null
                        ? (patient.getFirstName() + " " + patient.getLastName()).trim()
                        : null)
                .visitNumber(visit != null ? visit.getVisitNumber() : null)
                .zone(visit != null ? visit.getCurrentEdZone() : null)
                .bedLabel(visit != null && visit.getCurrentBed() != null
                        ? visit.getCurrentBed().getCode()
                        : null)
                .createdAt(dose.getCreatedAt())
                .build();
    }
}
