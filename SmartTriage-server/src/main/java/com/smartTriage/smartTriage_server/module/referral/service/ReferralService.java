package com.smartTriage.smartTriage_server.module.referral.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.referral.dto.*;
import com.smartTriage.smartTriage_server.module.referral.entity.Referral;
import com.smartTriage.smartTriage_server.module.referral.repository.ReferralRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ReferralService — manages inter-hospital referrals and transfers.
 *
 * Aligned with Rwanda's national referral system hierarchy and MoH referral documentation standards.
 * Tracks full lifecycle: initiation -> facility contact -> acceptance -> stabilization -> departure -> arrival -> completion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferralService {

    private final ReferralRepository referralRepository;
    private final VisitRepository visitRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final VitalSignsRepository vitalSignsRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Africa/Kigali"));

    /**
     * Initiate a new referral. Auto-generates a clinical summary from visit data.
     * Generates a CRITICAL alert for RED/ORANGE triage category patients.
     */
    @Transactional
    public Referral initiateReferral(UUID visitId, InitiateReferralRequest request) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));

        if (referralRepository.existsActiveReferralForVisit(visitId)) {
            throw new IllegalStateException("An active referral already exists for this visit");
        }

        Instant now = Instant.now();
        String clinicalSummary = generateClinicalSummaryFromVisit(visit);

        Referral referral = Referral.builder()
                .visit(visit)
                .referralType(request.getReferralType())
                .status(ReferralStatus.INITIATED)
                .referringHospital(visit.getHospital())
                .referringClinician(request.getReferringClinician())
                .referringClinicianPhone(request.getReferringClinicianPhone())
                .receivingHospitalName(request.getReceivingHospitalName())
                .receivingHospitalCode(request.getReceivingHospitalCode())
                .referralReason(request.getReferralReason())
                .clinicalSummary(clinicalSummary)
                .currentDiagnosis(request.getCurrentDiagnosis())
                .currentTriageCategory(visit.getCurrentTriageCategory())
                .currentTewsScore(visit.getCurrentTewsScore())
                .interventionsGiven(request.getInterventionsGiven())
                .ongoingTreatment(request.getOngoingTreatment())
                .transportMode(request.getTransportMode())
                .escortRequired(request.getEscortRequired())
                .escortName(request.getEscortName())
                .escortDesignation(request.getEscortDesignation())
                .estimatedTransferTimeMinutes(request.getEstimatedTransferTimeMinutes())
                .rhmisCaseNumber(request.getRhmisCaseNumber())
                .samuRequestNumber(request.getSamuRequestNumber())
                .initiatedAt(now)
                .notes(request.getNotes())
                .build();

        referral = referralRepository.save(referral);

        // Generate CRITICAL alert for RED or ORANGE patients
        if (visit.getCurrentTriageCategory() == TriageCategory.RED
                || visit.getCurrentTriageCategory() == TriageCategory.ORANGE) {
            generateReferralAlert(visit, referral);
        }

        log.info("Referral initiated: id={}, type={}, visit={}, from={} to={}",
                referral.getId(), referral.getReferralType(), visit.getVisitNumber(),
                visit.getHospital().getName(), request.getReceivingHospitalName());

        return referral;
    }

    /**
     * Record that the receiving facility has been contacted.
     */
    @Transactional
    public Referral contactReceivingFacility(UUID referralId, ContactFacilityRequest request) {
        Referral referral = findActiveReferral(referralId);
        validateStatusTransition(referral, ReferralStatus.RECEIVING_FACILITY_CONTACTED);

        referral.setStatus(ReferralStatus.RECEIVING_FACILITY_CONTACTED);
        referral.setReceivingContactedAt(Instant.now());

        if (request.getReceivingClinician() != null) {
            referral.setReceivingClinician(request.getReceivingClinician());
        }
        if (request.getReceivingClinicianPhone() != null) {
            referral.setReceivingClinicianPhone(request.getReceivingClinicianPhone());
        }
        if (request.getNotes() != null) {
            referral.setNotes(appendNotes(referral.getNotes(), request.getNotes()));
        }

        referral = referralRepository.save(referral);
        log.info("Receiving facility contacted for referral: id={}", referralId);
        return referral;
    }

    /**
     * Record acceptance by the receiving facility.
     */
    @Transactional
    public Referral recordAcceptance(UUID referralId, AcceptReferralRequest request) {
        Referral referral = findActiveReferral(referralId);
        validateStatusTransition(referral, ReferralStatus.ACCEPTED);

        referral.setStatus(ReferralStatus.ACCEPTED);
        referral.setAcceptedAt(Instant.now());

        if (request.getReceivingClinician() != null) {
            referral.setReceivingClinician(request.getReceivingClinician());
        }
        if (request.getReceivingClinicianPhone() != null) {
            referral.setReceivingClinicianPhone(request.getReceivingClinicianPhone());
        }
        if (request.getNotes() != null) {
            referral.setNotes(appendNotes(referral.getNotes(), request.getNotes()));
        }

        referral = referralRepository.save(referral);
        log.info("Referral accepted by receiving facility: id={}", referralId);
        return referral;
    }

    /**
     * Record decline by the receiving facility. The system logs the decline reason.
     */
    @Transactional
    public Referral recordDecline(UUID referralId, String reason) {
        Referral referral = findActiveReferral(referralId);
        validateStatusTransition(referral, ReferralStatus.DECLINED);

        referral.setStatus(ReferralStatus.DECLINED);
        referral.setNotes(appendNotes(referral.getNotes(), "DECLINED: " + reason));

        referral = referralRepository.save(referral);
        log.warn("Referral declined: id={}, reason={}", referralId, reason);
        return referral;
    }

    /**
     * Record that the patient has been stabilized for transfer.
     */
    @Transactional
    public Referral recordStabilization(UUID referralId) {
        Referral referral = findActiveReferral(referralId);
        validateStatusTransition(referral, ReferralStatus.PATIENT_STABILIZED);

        referral.setStatus(ReferralStatus.PATIENT_STABILIZED);
        referral.setStabilizedAt(Instant.now());

        referral = referralRepository.save(referral);
        log.info("Patient stabilized for referral: id={}", referralId);
        return referral;
    }

    /**
     * Record patient departure. Validates stabilization checklist is complete.
     * Generates an alert if checklist is incomplete.
     */
    @Transactional
    public Referral recordDeparture(UUID referralId, DepartureRequest request) {
        Referral referral = findActiveReferral(referralId);
        validateStatusTransition(referral, ReferralStatus.IN_TRANSIT);

        // Validate stabilization checklist
        if (!isStabilizationChecklistComplete(referral)) {
            generateStabilizationIncompleteAlert(referral);
            log.warn("Stabilization checklist incomplete for referral: id={}", referralId);
        }

        Instant now = Instant.now();
        referral.setStatus(ReferralStatus.IN_TRANSIT);
        referral.setDepartedAt(now);

        if (request.getTransportMode() != null) {
            referral.setTransportMode(request.getTransportMode());
        }
        if (request.getEscortRequired() != null) {
            referral.setEscortRequired(request.getEscortRequired());
        }
        if (request.getEscortName() != null) {
            referral.setEscortName(request.getEscortName());
        }
        if (request.getEscortDesignation() != null) {
            referral.setEscortDesignation(request.getEscortDesignation());
        }
        if (request.getSamuRequestNumber() != null) {
            referral.setSamuRequestNumber(request.getSamuRequestNumber());
        }
        if (request.getNotes() != null) {
            referral.setNotes(appendNotes(referral.getNotes(), request.getNotes()));
        }

        referral = referralRepository.save(referral);
        log.info("Patient departed for referral: id={}, transport={}", referralId, referral.getTransportMode());
        return referral;
    }

    /**
     * Record patient arrival at the receiving facility.
     */
    @Transactional
    public Referral recordArrival(UUID referralId) {
        Referral referral = findActiveReferral(referralId);
        validateStatusTransition(referral, ReferralStatus.RECEIVED_AT_DESTINATION);

        Instant now = Instant.now();
        referral.setStatus(ReferralStatus.RECEIVED_AT_DESTINATION);
        referral.setArrivedAt(now);

        // Compute actual transfer time
        if (referral.getDepartedAt() != null) {
            long minutes = Duration.between(referral.getDepartedAt(), now).toMinutes();
            referral.setActualTransferTimeMinutes((int) minutes);
        }

        referral = referralRepository.save(referral);
        log.info("Patient arrived at destination for referral: id={}, actualTransferMinutes={}",
                referralId, referral.getActualTransferTimeMinutes());
        return referral;
    }

    /**
     * Mark referral as complete.
     */
    @Transactional
    public Referral completeReferral(UUID referralId) {
        Referral referral = findActiveReferral(referralId);
        validateStatusTransition(referral, ReferralStatus.COMPLETED);

        referral.setStatus(ReferralStatus.COMPLETED);
        referral.setCompletedAt(Instant.now());

        referral = referralRepository.save(referral);
        log.info("Referral completed: id={}", referralId);
        return referral;
    }

    /**
     * Cancel referral with a reason.
     */
    @Transactional
    public Referral cancelReferral(UUID referralId, String reason) {
        Referral referral = findActiveReferral(referralId);

        if (referral.getStatus() == ReferralStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel a completed referral");
        }

        referral.setStatus(ReferralStatus.CANCELLED);
        referral.setCompletedAt(Instant.now());
        referral.setNotes(appendNotes(referral.getNotes(), "CANCELLED: " + reason));

        referral = referralRepository.save(referral);
        log.info("Referral cancelled: id={}, reason={}", referralId, reason);
        return referral;
    }

    /**
     * Get all active (non-terminal) referrals for a hospital.
     */
    public List<Referral> getActiveReferrals(UUID hospitalId) {
        return referralRepository.findActiveReferralsByHospital(hospitalId);
    }

    /**
     * Get the most recent referral for a visit.
     */
    public Referral getReferralForVisit(UUID visitId) {
        return referralRepository.findFirstByVisitIdAndIsActiveTrueOrderByInitiatedAtDesc(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Referral", "visitId", visitId));
    }

    /**
     * Generate a standardized referral summary document per Rwanda MoH format.
     */
    public ReferralSummaryResponse generateReferralSummary(UUID referralId) {
        Referral referral = findActiveReferral(referralId);
        Visit visit = referral.getVisit();
        var patient = visit.getPatient();

        StringBuilder sb = new StringBuilder();
        sb.append("=== RWANDA MINISTRY OF HEALTH - REFERRAL FORM ===\n\n");

        sb.append("--- REFERRAL INFORMATION ---\n");
        sb.append("Referral Type: ").append(referral.getReferralType()).append("\n");
        sb.append("Status: ").append(referral.getStatus()).append("\n");
        if (referral.getInitiatedAt() != null) {
            sb.append("Initiated: ").append(DATE_FORMATTER.format(referral.getInitiatedAt())).append("\n");
        }
        if (referral.getRhmisCaseNumber() != null) {
            sb.append("RHMIS Case Number: ").append(referral.getRhmisCaseNumber()).append("\n");
        }
        sb.append("\n");

        sb.append("--- PATIENT INFORMATION ---\n");
        sb.append("Name: ").append(patient.getFirstName()).append(" ").append(patient.getLastName()).append("\n");
        if (patient.getDateOfBirth() != null) {
            sb.append("Date of Birth: ").append(patient.getDateOfBirth()).append("\n");
            sb.append("Age: ").append(patient.getAgeInYears()).append(" years\n");
        }
        if (patient.getGender() != null) {
            sb.append("Gender: ").append(patient.getGender()).append("\n");
        }
        if (patient.getNationalId() != null) {
            sb.append("National ID: ").append(patient.getNationalId()).append("\n");
        }
        if (patient.getMedicalRecordNumber() != null) {
            sb.append("MRN: ").append(patient.getMedicalRecordNumber()).append("\n");
        }
        if (patient.getBloodType() != null) {
            sb.append("Blood Type: ").append(patient.getBloodType()).append("\n");
        }
        if (patient.getKnownAllergies() != null) {
            sb.append("Known Allergies: ").append(patient.getKnownAllergies()).append("\n");
        }
        if (patient.getChronicConditions() != null) {
            sb.append("Chronic Conditions: ").append(patient.getChronicConditions()).append("\n");
        }
        sb.append("\n");

        sb.append("--- REFERRING FACILITY ---\n");
        sb.append("Hospital: ").append(referral.getReferringHospital().getName()).append("\n");
        sb.append("Clinician: ").append(referral.getReferringClinician()).append("\n");
        if (referral.getReferringClinicianPhone() != null) {
            sb.append("Phone: ").append(referral.getReferringClinicianPhone()).append("\n");
        }
        sb.append("\n");

        sb.append("--- RECEIVING FACILITY ---\n");
        sb.append("Hospital: ").append(referral.getReceivingHospitalName()).append("\n");
        if (referral.getReceivingClinician() != null) {
            sb.append("Accepting Clinician: ").append(referral.getReceivingClinician()).append("\n");
        }
        if (referral.getReceivingClinicianPhone() != null) {
            sb.append("Phone: ").append(referral.getReceivingClinicianPhone()).append("\n");
        }
        sb.append("\n");

        sb.append("--- CLINICAL DETAILS ---\n");
        sb.append("Visit Number: ").append(visit.getVisitNumber()).append("\n");
        if (visit.getChiefComplaint() != null) {
            sb.append("Chief Complaint: ").append(visit.getChiefComplaint()).append("\n");
        }
        if (referral.getCurrentTriageCategory() != null) {
            sb.append("Triage Category: ").append(referral.getCurrentTriageCategory()).append("\n");
        }
        if (referral.getCurrentTewsScore() != null) {
            sb.append("TEWS Score: ").append(referral.getCurrentTewsScore()).append("\n");
        }
        sb.append("Reason for Referral: ").append(referral.getReferralReason()).append("\n");
        if (referral.getCurrentDiagnosis() != null) {
            sb.append("Current Diagnosis: ").append(referral.getCurrentDiagnosis()).append("\n");
        }
        sb.append("\nClinical Summary:\n").append(referral.getClinicalSummary()).append("\n");
        if (referral.getInterventionsGiven() != null) {
            sb.append("\nInterventions Given:\n").append(referral.getInterventionsGiven()).append("\n");
        }
        if (referral.getOngoingTreatment() != null) {
            sb.append("\nOngoing Treatment:\n").append(referral.getOngoingTreatment()).append("\n");
        }
        sb.append("\n");

        sb.append("--- STABILIZATION CHECKLIST ---\n");
        sb.append("Airway Secured: ").append(boolToCheck(referral.getAirwaySecured())).append("\n");
        sb.append("Breathing Stable: ").append(boolToCheck(referral.getBreathingStable())).append("\n");
        sb.append("Circulation Stable: ").append(boolToCheck(referral.getCirculationStable())).append("\n");
        sb.append("IV Access Established: ").append(boolToCheck(referral.getIvAccessEstablished())).append("\n");
        sb.append("Medications Documented: ").append(boolToCheck(referral.getMedicationsDocumented())).append("\n");
        sb.append("Allergies Documented: ").append(boolToCheck(referral.getAllergiesDocumented())).append("\n");
        sb.append("Blood Type Documented: ").append(boolToCheck(referral.getBloodTypeDocumented())).append("\n");
        sb.append("Consent Obtained: ").append(boolToCheck(referral.getConsentObtained())).append("\n");
        sb.append("Referral Form Completed: ").append(boolToCheck(referral.getReferralFormCompleted())).append("\n");
        sb.append("Patient ID Band Applied: ").append(boolToCheck(referral.getPatientIdBandApplied())).append("\n");
        sb.append("\n");

        sb.append("--- TRANSFER LOGISTICS ---\n");
        if (referral.getTransportMode() != null) {
            sb.append("Transport Mode: ").append(referral.getTransportMode()).append("\n");
        }
        if (referral.getSamuRequestNumber() != null) {
            sb.append("SAMU Request Number: ").append(referral.getSamuRequestNumber()).append("\n");
        }
        if (Boolean.TRUE.equals(referral.getEscortRequired())) {
            sb.append("Escort Required: Yes\n");
            if (referral.getEscortName() != null) {
                sb.append("Escort: ").append(referral.getEscortName());
                if (referral.getEscortDesignation() != null) {
                    sb.append(" (").append(referral.getEscortDesignation()).append(")");
                }
                sb.append("\n");
            }
        }
        if (referral.getDepartedAt() != null) {
            sb.append("Departed: ").append(DATE_FORMATTER.format(referral.getDepartedAt())).append("\n");
        }
        if (referral.getArrivedAt() != null) {
            sb.append("Arrived: ").append(DATE_FORMATTER.format(referral.getArrivedAt())).append("\n");
        }
        if (referral.getActualTransferTimeMinutes() != null) {
            sb.append("Transfer Duration: ").append(referral.getActualTransferTimeMinutes()).append(" minutes\n");
        }

        if (referral.getNotes() != null) {
            sb.append("\n--- NOTES ---\n");
            sb.append(referral.getNotes()).append("\n");
        }

        sb.append("\n=== END OF REFERRAL DOCUMENT ===");

        return ReferralSummaryResponse.builder()
                .referralId(referral.getId())
                .summaryDocument(sb.toString())
                .build();
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private Referral findActiveReferral(UUID referralId) {
        return referralRepository.findByIdAndIsActiveTrue(referralId)
                .orElseThrow(() -> new ResourceNotFoundException("Referral", "id", referralId));
    }

    private String generateClinicalSummaryFromVisit(Visit visit) {
        StringBuilder sb = new StringBuilder();
        var patient = visit.getPatient();

        sb.append("Patient: ").append(patient.getFirstName()).append(" ").append(patient.getLastName());
        if (patient.getDateOfBirth() != null) {
            sb.append(", Age: ").append(patient.getAgeInYears()).append(" years");
        }
        if (patient.getGender() != null) {
            sb.append(", Gender: ").append(patient.getGender());
        }
        sb.append("\n");

        sb.append("Visit: ").append(visit.getVisitNumber());
        sb.append(", Arrival: ").append(DATE_FORMATTER.format(visit.getArrivalTime()));
        if (visit.getArrivalMode() != null) {
            sb.append(", Mode: ").append(visit.getArrivalMode());
        }
        sb.append("\n");

        if (visit.getChiefComplaint() != null) {
            sb.append("Chief Complaint: ").append(visit.getChiefComplaint()).append("\n");
        }

        if (visit.getCurrentTriageCategory() != null) {
            sb.append("Triage Category: ").append(visit.getCurrentTriageCategory())
                    .append(" (").append(visit.getCurrentTriageCategory().getDescription()).append(")");
            if (visit.getCurrentTewsScore() != null) {
                sb.append(", TEWS: ").append(visit.getCurrentTewsScore());
            }
            sb.append("\n");
        }

        sb.append("Status: ").append(visit.getStatus()).append("\n");

        // Include latest vitals
        Optional<VitalSigns> latestVitals = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visit.getId());
        if (latestVitals.isPresent()) {
            VitalSigns vitals = latestVitals.get();
            sb.append("Latest Vitals (").append(DATE_FORMATTER.format(vitals.getRecordedAt())).append("):");
            if (vitals.getHeartRate() != null) sb.append(" HR=").append(vitals.getHeartRate());
            if (vitals.getSystolicBp() != null && vitals.getDiastolicBp() != null) {
                sb.append(" BP=").append(vitals.getSystolicBp()).append("/").append(vitals.getDiastolicBp());
            }
            if (vitals.getRespiratoryRate() != null) sb.append(" RR=").append(vitals.getRespiratoryRate());
            if (vitals.getTemperature() != null) sb.append(" Temp=").append(vitals.getTemperature()).append("C");
            if (vitals.getSpo2() != null) sb.append(" SpO2=").append(vitals.getSpo2()).append("%");
            if (vitals.getAvpu() != null) sb.append(" AVPU=").append(vitals.getAvpu());
            if (vitals.getGcsScore() != null) sb.append(" GCS=").append(vitals.getGcsScore());
            if (vitals.getBloodGlucose() != null) sb.append(" Glucose=").append(vitals.getBloodGlucose()).append("mmol/L");
            sb.append("\n");
        }

        // Include patient medical history
        if (patient.getKnownAllergies() != null) {
            sb.append("Allergies: ").append(patient.getKnownAllergies()).append("\n");
        }
        if (patient.getChronicConditions() != null) {
            sb.append("Chronic Conditions: ").append(patient.getChronicConditions()).append("\n");
        }
        if (patient.getBloodType() != null) {
            sb.append("Blood Type: ").append(patient.getBloodType()).append("\n");
        }

        return sb.toString();
    }

    private boolean isStabilizationChecklistComplete(Referral referral) {
        return Boolean.TRUE.equals(referral.getAirwaySecured())
                && Boolean.TRUE.equals(referral.getBreathingStable())
                && Boolean.TRUE.equals(referral.getCirculationStable())
                && Boolean.TRUE.equals(referral.getIvAccessEstablished())
                && Boolean.TRUE.equals(referral.getMedicationsDocumented())
                && Boolean.TRUE.equals(referral.getAllergiesDocumented())
                && Boolean.TRUE.equals(referral.getBloodTypeDocumented())
                && Boolean.TRUE.equals(referral.getConsentObtained())
                && Boolean.TRUE.equals(referral.getReferralFormCompleted())
                && Boolean.TRUE.equals(referral.getPatientIdBandApplied());
    }

    private void validateStatusTransition(Referral referral, ReferralStatus newStatus) {
        ReferralStatus current = referral.getStatus();

        if (current == ReferralStatus.COMPLETED || current == ReferralStatus.CANCELLED) {
            throw new IllegalStateException(
                    String.format("Cannot transition from %s to %s — referral is in terminal state", current, newStatus));
        }

        // Allow flexible transitions but log unexpected ones
        boolean expectedTransition = switch (newStatus) {
            case RECEIVING_FACILITY_CONTACTED -> current == ReferralStatus.INITIATED;
            case ACCEPTED -> current == ReferralStatus.RECEIVING_FACILITY_CONTACTED;
            case DECLINED -> current == ReferralStatus.RECEIVING_FACILITY_CONTACTED;
            case PATIENT_STABILIZED -> current == ReferralStatus.ACCEPTED;
            case IN_TRANSIT -> current == ReferralStatus.PATIENT_STABILIZED || current == ReferralStatus.ACCEPTED;
            case RECEIVED_AT_DESTINATION -> current == ReferralStatus.IN_TRANSIT;
            case COMPLETED -> current == ReferralStatus.RECEIVED_AT_DESTINATION;
            default -> true;
        };

        if (!expectedTransition) {
            log.warn("Unexpected status transition for referral {}: {} -> {}", referral.getId(), current, newStatus);
        }
    }

    private void generateReferralAlert(Visit visit, Referral referral) {
        String title = String.format("REFERRAL INITIATED: %s patient — %s",
                referral.getCurrentTriageCategory(), referral.getReferralType());
        String message = String.format(
                "Inter-hospital referral initiated for %s patient. Visit: %s. From: %s To: %s. Reason: %s.",
                referral.getCurrentTriageCategory(),
                visit.getVisitNumber(),
                referral.getReferringHospital().getName(),
                referral.getReceivingHospitalName(),
                referral.getReferralReason());

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.REFERRAL_INITIATED)
                .severity(AlertSeverity.CRITICAL)
                .title(title)
                .message(message)
                .autoGenerated(true)
                .escalationTier(1)
                .build();

        clinicalAlertRepository.save(alert);
        log.info("CRITICAL alert generated for referral initiation: visit={}, category={}",
                visit.getId(), referral.getCurrentTriageCategory());
    }

    private void generateStabilizationIncompleteAlert(Referral referral) {
        Visit visit = referral.getVisit();
        String title = "STABILIZATION CHECKLIST INCOMPLETE — Patient departing for transfer";
        String message = String.format(
                "Patient departing for transfer with incomplete stabilization checklist. Visit: %s. Referral to: %s. " +
                        "Review checklist items before allowing departure.",
                visit.getVisitNumber(), referral.getReceivingHospitalName());

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.REFERRAL_STABILIZATION_INCOMPLETE)
                .severity(AlertSeverity.HIGH)
                .title(title)
                .message(message)
                .autoGenerated(true)
                .escalationTier(1)
                .build();

        clinicalAlertRepository.save(alert);
    }

    private String boolToCheck(Boolean value) {
        return Boolean.TRUE.equals(value) ? "[X]" : "[ ]";
    }

    private String appendNotes(String existing, String additional) {
        if (existing == null || existing.isBlank()) {
            return additional;
        }
        return existing + "\n" + additional;
    }
}
