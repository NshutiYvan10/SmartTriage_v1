package com.smartTriage.smartTriage_server.module.ems.mapper;

import com.smartTriage.smartTriage_server.module.ems.dto.EmsInterventionResponse;
import com.smartTriage.smartTriage_server.module.ems.dto.EmsRunResponse;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsIntervention;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.service.UnidentifiedPatientNameService;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;

import java.util.List;
import java.util.stream.Collectors;

public final class EmsRunMapper {

    private EmsRunMapper() {}

    public static EmsRunResponse toResponse(EmsRun r) {
        return toResponse(r, null);
    }

    public static EmsRunResponse toResponse(EmsRun r, List<EmsIntervention> interventions) {
        Visit visit = r.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;
        return EmsRunResponse.builder()
                .id(r.getId())
                .hospitalId(r.getHospital() != null ? r.getHospital().getId() : null)
                .visitId(visit != null ? visit.getId() : null)
                .patientId(patient != null ? patient.getId() : null)
                .patientName(patientName(visit, patient))
                .visitNumber(visit != null ? visit.getVisitNumber() : null)
                .paramedicUserId(r.getParamedic() != null ? r.getParamedic().getId() : null)
                .paramedicName(r.getParamedicName())
                .service(r.getService())
                .unitCallsign(r.getUnitCallsign())
                .dispatchedAt(r.getDispatchedAt())
                .sceneArrivedAt(r.getSceneArrivedAt())
                .sceneLeftAt(r.getSceneLeftAt())
                .edArrivedAt(r.getEdArrivedAt())
                .handedOffAt(r.getHandedOffAt())
                .cancelledAt(r.getCancelledAt())
                .cancelReason(r.getCancelReason())
                .patientAgeYears(r.getPatientAgeYears())
                .patientSex(r.getPatientSex())
                .incidentLocation(r.getIncidentLocation())
                .mechanism(r.getMechanism())
                .historySummary(r.getHistorySummary())
                .injuriesObserved(r.getInjuriesObserved())
                .fieldTriageCategory(r.getFieldTriageCategory())
                .fieldTriageReason(r.getFieldTriageReason())
                .fieldTewsScore(r.getFieldTewsScore())
                .fieldTriageDecisionPath(r.getFieldTriageDecisionPath())
                .fieldTriageIsChild(r.getFieldTriageIsChild())
                .fieldTriageInput(r.getFieldTriageInput())
                .fieldGcs(r.getFieldGcs())
                .fieldRespRate(r.getFieldRespRate())
                .fieldHr(r.getFieldHr())
                .fieldSbp(r.getFieldSbp())
                .fieldDbp(r.getFieldDbp())
                .fieldSpo2(r.getFieldSpo2())
                .fieldTemp(r.getFieldTemp())
                .fieldGlucose(r.getFieldGlucose())
                .status(r.getStatus())
                .handedOffToUserId(r.getHandedOffTo() != null ? r.getHandedOffTo().getId() : null)
                .handedOffToName(r.getHandedOffToName())
                .handoverAcknowledgementText(r.getHandoverAcknowledgementText())
                .etaMinutes(r.getEtaMinutes())
                .notes(r.getNotes())
                .lightsActive(r.isLightsActive())
                .lightsActivatedAt(r.getLightsActivatedAt())
                .preArrivalAckedAt(r.getPreArrivalAckedAt())
                .preArrivalAckedByName(r.getPreArrivalAckedByName())
                .arrivalAckedAt(r.getArrivalAckedAt())
                .arrivalAckedByName(r.getArrivalAckedByName())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .interventions(interventions == null ? null
                        : interventions.stream().map(EmsRunMapper::toInterventionResponse).collect(Collectors.toList()))
                .build();
    }

    /**
     * Patient display name for a run's board/card. Null-safe: a pre-arrival run
     * with no linked visit/patient yet returns null (the FE PatientContextLine
     * renders "Unidentified patient"). An unidentified placeholder patient
     * renders its "Unknown {label}" display name so the row reads as a real,
     * tracked arrival rather than blank.
     */
    private static String patientName(Visit visit, Patient patient) {
        if (patient == null) {
            return null;
        }
        if (patient.isUnidentified()) {
            return UnidentifiedPatientNameService.buildDisplayName(
                    patient.getPlaceholderLabel(), visit != null && visit.isPediatric());
        }
        String name = ((patient.getFirstName() == null ? "" : patient.getFirstName()) + " "
                + (patient.getLastName() == null ? "" : patient.getLastName())).trim();
        return name.isEmpty() ? null : name;
    }

    public static EmsInterventionResponse toInterventionResponse(EmsIntervention i) {
        return EmsInterventionResponse.builder()
                .id(i.getId())
                .type(i.getType())
                .givenAt(i.getGivenAt())
                .givenByName(i.getGivenByName())
                .detail(i.getDetail())
                .dose(i.getDose())
                .route(i.getRoute())
                .outcome(i.getOutcome())
                .notes(i.getNotes())
                .build();
    }
}
