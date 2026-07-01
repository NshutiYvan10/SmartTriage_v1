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
                .edRetriageDueAt(visit != null ? visit.getEdRetriageDueAt() : null)
                .lifecycleStage(lifecycleStage(r))
                .routingTarget(routingTarget(r, visit))
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

    /**
     * The explicit case-lifecycle stage, derived so every surface (card stepper,
     * chart, board) agrees on where the ambulance case is:
     * <ul>
     *   <li>CANCELLED / HANDED_OFF — terminal (the dashboard card resolves).</li>
     *   <li>RECEIVED — physically at the door AND the ED has acknowledged receipt
     *       (arrivalAckedAt) but the formal read-back handover isn't done yet.</li>
     *   <li>AT_DOOR — arrived, receipt not yet acknowledged.</li>
     *   <li>EN_ROUTE — pre-arrival sent, inbound.</li>
     *   <li>DISPATCHED — created, not yet announced to the ED.</li>
     * </ul>
     */
    private static String lifecycleStage(EmsRun r) {
        if (r.getStatus() == null) return "DISPATCHED";
        return switch (r.getStatus()) {
            case CANCELLED -> "CANCELLED";
            case HANDED_OFF -> "HANDED_OFF";
            case ARRIVED -> r.getArrivalAckedAt() != null ? "RECEIVED" : "AT_DOOR";
            case EN_ROUTE -> "EN_ROUTE";
            case DISPATCHED -> "DISPATCHED";
        };
    }

    /**
     * Acuity-split destination for the card's routing badge. Once the visit has a
     * real ED-zone placement (RED/ORANGE → Resus/Acute on arrival) that zone is
     * authoritative; otherwise it is projected from the field-triage category
     * (RED→RESUS, ORANGE→ACUTE, YELLOW/GREEN/BLUE→TRIAGE_QUEUE). Null until a field
     * call exists. Pure (no service deps) — mirrors ZoneRoutingService's policy.
     */
    private static String routingTarget(EmsRun r, Visit visit) {
        if (visit != null && visit.getCurrentEdZone() != null) {
            return visit.getCurrentEdZone().name();
        }
        String cat = r.getFieldTriageCategory();
        if (cat == null) return null;
        return switch (cat.trim().toUpperCase()) {
            case "RED" -> "RESUS";
            case "ORANGE" -> "ACUTE";
            case "YELLOW", "GREEN", "BLUE" -> "TRIAGE_QUEUE";
            default -> null;
        };
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
