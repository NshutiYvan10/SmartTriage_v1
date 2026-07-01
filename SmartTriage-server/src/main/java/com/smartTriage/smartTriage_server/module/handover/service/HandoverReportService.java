package com.smartTriage.smartTriage_server.module.handover.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote;
import com.smartTriage.smartTriage_server.module.clinical.entity.Diagnosis;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.DiagnosisRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.handover.dto.GenerateShiftHandoverRequest;
import com.smartTriage.smartTriage_server.module.handover.dto.HandoverReportResponse;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
import com.smartTriage.smartTriage_server.module.handover.mapper.HandoverReportMapper;
import com.smartTriage.smartTriage_server.module.handover.repository.HandoverReportRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.isolation.entity.InfectionScreening;
import com.smartTriage.smartTriage_server.module.isolation.repository.InfectionScreeningRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PatientAllergy;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientAllergyRepository;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsIntervention;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsInterventionRepository;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.fasttrack.entity.FastTrackActivation;
import com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository;
import com.smartTriage.smartTriage_server.module.sepsis.entity.SepsisScreening;
import com.smartTriage.smartTriage_server.module.sepsis.repository.SepsisScreeningRepository;
import com.smartTriage.smartTriage_server.module.icu.entity.IcuEscalation;
import com.smartTriage.smartTriage_server.module.icu.repository.IcuEscalationRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignEvent;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignStatus;
import com.smartTriage.smartTriage_server.module.clinicalsigns.repository.ClinicalSignEventRepository;
import com.smartTriage.smartTriage_server.module.documentation.entity.ClinicalDocument;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.pathway.entity.PathwayActivation;
import com.smartTriage.smartTriage_server.module.pathway.repository.PathwayActivationRepository;
import com.smartTriage.smartTriage_server.module.zonetransfer.entity.ZoneTransfer;
import com.smartTriage.smartTriage_server.module.zonetransfer.repository.ZoneTransferRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.PatientChronicCondition;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientChronicConditionRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HandoverReportService — auto-generates comprehensive patient summaries for shift
 * handovers, ward transfers, discharge summaries, and inter-hospital transfers.
 *
 * Compiles all clinical data from a visit into structured report sections to ensure
 * safe continuity of care during transitions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HandoverReportService {

    private final HandoverReportRepository handoverReportRepository;
    private final VisitRepository visitRepository;
    private final HospitalRepository hospitalRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final InvestigationRepository investigationRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final MedicationAdministrationRepository medicationAdministrationRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ClinicalNoteRepository clinicalNoteRepository;
    private final PatientAllergyRepository patientAllergyRepository;
    private final InfectionScreeningRepository infectionScreeningRepository;
    // V73 — previously-omitted clinical domains now folded into the report.
    private final EmsRunRepository emsRunRepository;
    private final EmsInterventionRepository emsInterventionRepository;
    private final FastTrackActivationRepository fastTrackActivationRepository;
    private final SepsisScreeningRepository sepsisScreeningRepository;
    private final IcuEscalationRepository icuEscalationRepository;
    private final HypoglycemiaEventRepository hypoglycemiaEventRepository;
    private final ClinicalSignEventRepository clinicalSignEventRepository;
    private final ClinicalDocumentRepository clinicalDocumentRepository;
    private final LabOrderRepository labOrderRepository;
    private final PathwayActivationRepository pathwayActivationRepository;
    private final ZoneTransferRepository zoneTransferRepository;
    private final PatientChronicConditionRepository patientChronicConditionRepository;
    /**
     * V67 — builds the dedicated medication-audit section (typed orders,
     * dose-by-dose log with actors/witnesses/reasons, PRN usage,
     * infusion state, modification chain).
     */
    private final com.smartTriage.smartTriage_server.module.medication.service.MedicationScheduleService medicationScheduleService;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Kigali"));

    /**
     * Auto-compiles all patient data into a comprehensive handover report.
     */
    @Transactional
    public HandoverReport generateReport(UUID visitId, HandoverReportType type, String generatedByName, String notes) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));

        log.info("Generating {} report for visit {} (patient: {} {})",
                type, visit.getVisitNumber(),
                visit.getPatient().getFirstName(), visit.getPatient().getLastName());

        HandoverReport report = HandoverReport.builder()
                .visit(visit)
                .hospital(visit.getHospital())
                .reportType(type)
                .generatedAt(Instant.now())
                .generatedByName(generatedByName)
                .notes(notes)
                .patientSummary(buildPatientSummary(visit))
                .presentingComplaint(buildPresentingComplaint(visit))
                .triageSummary(buildTriageSummary(visit))
                .vitalSignsTrend(buildVitalSignsTrend(visit))
                .investigationsResults(buildInvestigationsResults(visit))
                .diagnosisSummary(buildDiagnosisSummary(visit))
                .treatmentSummary(buildTreatmentSummary(visit))
                .activeClinicalAlerts(buildActiveClinicalAlerts(visit))
                .outstandingTasks(buildOutstandingTasks(visit))
                .planOfCare(buildPlanOfCare(visit))
                .edTimeline(buildEdTimeline(visit))
                .prehospitalSummary(buildPrehospitalSummary(visit))
                .acuteProtocols(buildAcuteProtocols(visit))
                .proceduresDocuments(buildProceduresDocuments(visit))
                .medicationAudit(medicationScheduleService.buildMedicationAuditText(visit))
                .build();

        report = handoverReportRepository.save(report);
        log.info("Handover report generated: {} for visit {}", report.getId(), visit.getVisitNumber());
        return report;
    }

    /**
     * Mark a handover report as received/acknowledged.
     */
    @Transactional
    public HandoverReport acknowledgeHandover(UUID reportId, String receiverName) {
        HandoverReport report = handoverReportRepository.findByIdAndIsActiveTrue(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("HandoverReport", "id", reportId));

        report.setAcknowledged(true);
        report.setReceivedByName(receiverName);
        report.setReceivedAt(Instant.now());
        report.setAcknowledgedAt(Instant.now());

        report = handoverReportRepository.save(report);
        log.info("Handover report {} acknowledged by {}", reportId, receiverName);
        return report;
    }

    /**
     * Get all reports for a visit.
     */
    public List<HandoverReport> getReportsForVisit(UUID visitId) {
        return handoverReportRepository.findByVisitIdAndIsActiveTrueOrderByGeneratedAtDesc(visitId);
    }

    /**
     * Get all reports generated during a shift window.
     */
    public List<HandoverReport> getReportsForShift(UUID hospitalId, Instant shiftStart, Instant shiftEnd) {
        return handoverReportRepository.findReportsForShift(hospitalId, shiftStart, shiftEnd);
    }

    /**
     * Get a single report by ID.
     */
    public HandoverReport getReport(UUID reportId) {
        return handoverReportRepository.findByIdAndIsActiveTrue(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("HandoverReport", "id", reportId));
    }

    /* ─────────────────────────── DTO-returning variants ───────────────────────────
     * These map entity → DTO INSIDE the service transaction. That is mandatory here:
     * spring.jpa.open-in-view=false, and HandoverReport.visit / .hospital (and the
     * visit's patient / currentBed) are LAZY @ManyToOne. If the controller maps a
     * detached entity (after this tx has closed), touching any of those associations
     * throws LazyInitializationException → an unhandled 500 ("An unexpected error
     * occurred"). It bit the Charge Nurse shift-summary card intermittently: the
     * /shift list defaults to the last 12h, so an EMPTY window mapped fine while a
     * window with ≥1 report blew up. Mapping in-tx (as ClinicalAlertService does)
     * initialises the associations before the session closes. Self-invocation of the
     * entity methods runs their body within THIS method's active transaction. */

    @Transactional(readOnly = true)
    public List<HandoverReportResponse> getReportResponsesForVisit(UUID visitId) {
        return getReportsForVisit(visitId).stream().map(HandoverReportMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<HandoverReportResponse> getReportResponsesForShift(UUID hospitalId, Instant shiftStart, Instant shiftEnd) {
        return getReportsForShift(hospitalId, shiftStart, shiftEnd).stream().map(HandoverReportMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public HandoverReportResponse getReportResponse(UUID reportId) {
        return HandoverReportMapper.toResponse(getReport(reportId));
    }

    @Transactional
    public HandoverReportResponse generateReportResponse(UUID visitId, HandoverReportType type,
                                                         String generatedByName, String notes) {
        return HandoverReportMapper.toResponse(generateReport(visitId, type, generatedByName, notes));
    }

    @Transactional
    public HandoverReportResponse acknowledgeHandoverResponse(UUID reportId, String receiverName) {
        return HandoverReportMapper.toResponse(acknowledgeHandover(reportId, receiverName));
    }

    @Transactional
    public List<HandoverReportResponse> generateBulkShiftHandoverResponses(UUID hospitalId,
                                                                           GenerateShiftHandoverRequest request) {
        return generateBulkShiftHandover(hospitalId, request).stream().map(HandoverReportMapper::toResponse).toList();
    }

    /**
     * Generate handover reports for ALL active patients at once (shift change).
     */
    @Transactional
    public List<HandoverReport> generateBulkShiftHandover(UUID hospitalId, GenerateShiftHandoverRequest request) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        List<VisitStatus> activeStatuses = List.of(
                VisitStatus.AWAITING_TRIAGE, VisitStatus.TRIAGED,
                VisitStatus.AWAITING_ASSESSMENT, VisitStatus.UNDER_ASSESSMENT,
                VisitStatus.UNDER_TREATMENT, VisitStatus.UNDER_OBSERVATION,
                VisitStatus.PENDING_DISPOSITION
        );

        List<Visit> activeVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, activeStatuses);
        log.info("Generating bulk shift handover for {} active patients at hospital {}",
                activeVisits.size(), hospital.getName());

        List<HandoverReport> reports = new ArrayList<>();
        for (Visit visit : activeVisits) {
            try {
                HandoverReport report = generateReport(
                        visit.getId(),
                        HandoverReportType.SHIFT_HANDOVER,
                        request.getGeneratedByName(),
                        request.getNotes()
                );
                reports.add(report);
            } catch (Exception e) {
                log.error("Failed to generate handover report for visit {}: {}",
                        visit.getVisitNumber(), e.getMessage());
            }
        }

        log.info("Bulk shift handover complete: {} reports generated for hospital {}",
                reports.size(), hospital.getName());
        return reports;
    }

    // ====================================================================
    // SECTION BUILDERS
    // ====================================================================

    private String buildPatientSummary(Visit visit) {
        Patient patient = visit.getPatient();
        StringBuilder sb = new StringBuilder();

        // Unidentified placeholder — must be unmistakable so the incoming team
        // never mistakes "Unknown Alpha" for a real name, and knows identity is
        // an open, time-sensitive task.
        if (patient.isUnidentified()) {
            String label = patient.getPlaceholderLabel() != null
                    ? patient.getPlaceholderLabel() : patient.getLastName();
            sb.append("** UNIDENTIFIED PATIENT ** — registered as placeholder \"Unknown ")
              .append(label).append("\".\n");
            if (patient.getPlaceholderAssignedAt() != null) {
                long mins = Duration.between(patient.getPlaceholderAssignedAt(), Instant.now()).toMinutes();
                sb.append("   Identity UNRESOLVED for ").append(formatDuration(mins))
                  .append(" — resolve via 'Set Patient Identity' on the chart as soon as possible.\n");
            }
            sb.append("\n");
        }

        sb.append("Name: ").append(patient.getFirstName()).append(" ").append(patient.getLastName()).append("\n");

        if (patient.getDateOfBirth() != null) {
            sb.append("Age: ").append(patient.getAgeInYears()).append(" years\n");
            sb.append("Date of Birth: ").append(patient.getDateOfBirth()).append("\n");
        }
        if (patient.getGender() != null) {
            sb.append("Gender: ").append(patient.getGender()).append("\n");
        }
        if (patient.getMedicalRecordNumber() != null) {
            sb.append("MRN: ").append(patient.getMedicalRecordNumber()).append("\n");
        }
        if (patient.getNationalId() != null) {
            sb.append("National ID: ").append(patient.getNationalId()).append("\n");
        }
        sb.append("Visit Number: ").append(visit.getVisitNumber()).append("\n");
        sb.append("Pediatric: ").append(visit.isPediatric() ? "Yes" : "No").append("\n");

        // Current physical location — so the incoming clinician can find the patient.
        sb.append("Location: ");
        sb.append(visit.getCurrentEdZone() != null ? "Zone " + visit.getCurrentEdZone() : "Zone —");
        var bed = visit.getCurrentBed();
        if (bed != null) {
            sb.append(", Bed ").append(bed.getCode() != null ? bed.getCode() : "");
            if (bed.getLabel() != null && !bed.getLabel().isBlank()) {
                sb.append(" (").append(bed.getLabel()).append(")");
            }
        } else {
            sb.append(", no bed assigned");
        }
        sb.append("\n");

        sb.append(buildChronicConditionLine(patient));

        // Allergies — prefer the structured, verification-aware list (refuted
        // entries are excluded by findActiveByPatientId); fall back to the
        // legacy free-text only if no structured rows exist.
        sb.append(buildAllergyLine(patient));

        if (patient.getBloodType() != null) {
            sb.append("Blood Type: ").append(patient.getBloodType()).append("\n");
        }

        // Infection-control / isolation status.
        String isolation = buildIsolationLine(visit);
        if (isolation != null) {
            sb.append(isolation);
        }

        return sb.toString();
    }

    private String buildAllergyLine(Patient patient) {
        List<PatientAllergy> allergies = patientAllergyRepository.findActiveByPatientId(patient.getId());
        if (allergies.isEmpty()) {
            if (patient.getKnownAllergies() != null && !patient.getKnownAllergies().isBlank()) {
                return "Allergies (unverified free-text): " + patient.getKnownAllergies() + "\n";
            }
            return "Allergies: None known\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("** ALLERGIES (").append(allergies.size()).append(") **\n");
        for (PatientAllergy a : allergies) {
            String name = a.getAllergenName();
            if (name == null || name.isBlank()) name = "Unknown allergen";
            sb.append("   - ").append(name);
            if (a.getSeverity() != null) sb.append(" [").append(a.getSeverity()).append("]");
            if (a.getReaction() != null && !a.getReaction().isBlank()) sb.append(" — ").append(a.getReaction());
            if (a.getVerificationStatus() != null) sb.append(" (").append(a.getVerificationStatus()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Chronic conditions — prefer the structured, status-aware list (excludes
     * RESOLVED); fall back to the legacy free-text column only when no
     * structured rows exist so curated comorbidities aren't lost.
     */
    private String buildChronicConditionLine(Patient patient) {
        List<PatientChronicCondition> conditions =
                patientChronicConditionRepository.findActiveByPatientId(patient.getId());
        if (conditions.isEmpty()) {
            if (patient.getChronicConditions() != null && !patient.getChronicConditions().isBlank()) {
                return "Known Conditions: " + patient.getChronicConditions() + "\n";
            }
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Known Conditions (").append(conditions.size()).append("):\n");
        for (PatientChronicCondition c : conditions) {
            sb.append("   - ").append(c.getConditionName());
            if (c.getConditionCode() != null && !c.getConditionCode().isBlank()) {
                sb.append(" (").append(c.getConditionCode()).append(")");
            }
            if (c.getStatus() != null) sb.append(" [").append(c.getStatus().getLabel()).append("]");
            if (c.getNotes() != null && !c.getNotes().isBlank()) sb.append(" — ").append(c.getNotes());
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildIsolationLine(Visit visit) {
        List<InfectionScreening> screenings = infectionScreeningRepository
                .findByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(visit.getId());
        if (screenings.isEmpty()) return null;
        InfectionScreening latest = screenings.get(0);
        var iso = latest.getIsolationType();
        boolean active = iso != null && !"NONE".equalsIgnoreCase(iso.name())
                && latest.getIsolationEndedAt() == null;
        if (!active) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("** ISOLATION: ").append(iso);
        if (latest.getIsolationRoomAssigned() != null && !latest.getIsolationRoomAssigned().isBlank()) {
            sb.append(" — room ").append(latest.getIsolationRoomAssigned());
        }
        if (latest.getRiskLevel() != null) sb.append(" (risk: ").append(latest.getRiskLevel()).append(")");
        sb.append(" **\n");
        return sb.toString();
    }

    private static String formatDuration(long minutes) {
        if (minutes < 60) return minutes + " min";
        long h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    private String buildPresentingComplaint(Visit visit) {
        StringBuilder sb = new StringBuilder();
        if (visit.getChiefComplaint() != null && !visit.getChiefComplaint().isBlank()) {
            sb.append("Chief Complaint: ").append(visit.getChiefComplaint()).append("\n");
        }

        // Include history of complaint from clinical notes
        List<ClinicalNote> historyNotes = clinicalNoteRepository
                .findByVisitIdAndNoteTypeAndIsActiveTrueOrderByRecordedAtDesc(
                        visit.getId(), NoteType.HISTORY_OF_PRESENTING_COMPLAINT);
        if (!historyNotes.isEmpty()) {
            sb.append("\nHistory of Presenting Complaint:\n");
            for (ClinicalNote note : historyNotes) {
                sb.append("  [").append(TIME_FMT.format(note.getRecordedAt())).append("] ");
                if (note.getRecordedByName() != null) {
                    sb.append("(").append(note.getRecordedByName()).append(") ");
                }
                sb.append(note.getContent()).append("\n");
            }
        }

        return sb.length() > 0 ? sb.toString() : "No presenting complaint recorded.";
    }

    private String buildTriageSummary(Visit visit) {
        Pageable all = PageRequest.of(0, 100);
        List<TriageRecord> records = triageRecordRepository
                .findByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visit.getId(), all)
                .getContent();

        if (records.isEmpty()) {
            return "No triage records found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Total Triage Events: ").append(records.size()).append("\n");
        sb.append("Current Category: ").append(visit.getCurrentTriageCategory()).append("\n");
        if (visit.getCurrentTewsScore() != null) {
            sb.append("Current TEWS Score: ").append(visit.getCurrentTewsScore()).append("\n");
        }
        sb.append("Retriage Count: ").append(visit.getRetriageCount()).append("\n\n");

        sb.append("Triage History (most recent first):\n");
        for (TriageRecord record : records) {
            sb.append("  [").append(TIME_FMT.format(record.getTriageTime())).append("] ");
            sb.append("Category: ").append(record.getTriageCategory());
            sb.append(" | TEWS: ").append(record.getTewsScore());
            if (record.isRetriage()) {
                sb.append(" | RETRIAGE");
                if (record.getPreviousCategory() != null) {
                    sb.append(" (from ").append(record.getPreviousCategory()).append(")");
                }
            }
            if (record.isSystemTriggered()) {
                sb.append(" | SYSTEM-TRIGGERED");
            }
            // Who performed this (re)triage — accountability the report previously dropped.
            if (record.getTriageNurseName() != null && !record.getTriageNurseName().isBlank()) {
                sb.append(" | by ").append(record.getTriageNurseName());
            }
            if (record.getDecisionPath() != null) {
                sb.append("\n    Decision: ").append(record.getDecisionPath());
            }
            // Why — the documented rationale for the (re)triage.
            if (record.getClinicalNotes() != null && !record.getClinicalNotes().isBlank()) {
                sb.append("\n    Reason: ").append(record.getClinicalNotes());
            }
            // RED/ORANGE doctor-notification + attendance chain.
            if (record.getNotifiedDoctorName() != null && !record.getNotifiedDoctorName().isBlank()) {
                sb.append("\n    Doctor notified: ").append(record.getNotifiedDoctorName());
                if (record.getDoctorNotifiedAt() != null) {
                    sb.append(" at ").append(TIME_FMT.format(record.getDoctorNotifiedAt()));
                }
            }
            if (record.getAttendingDoctorName() != null && !record.getAttendingDoctorName().isBlank()) {
                sb.append("\n    Doctor attended: ").append(record.getAttendingDoctorName());
                if (record.getDoctorAttendedAt() != null) {
                    sb.append(" at ").append(TIME_FMT.format(record.getDoctorAttendedAt()));
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildVitalSignsTrend(Visit visit) {
        Pageable recent = PageRequest.of(0, 20);
        List<VitalSigns> vitals = vitalSignsRepository
                .findByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visit.getId(), recent)
                .getContent();

        if (vitals.isEmpty()) {
            return "No vital signs recorded.";
        }

        StringBuilder sb = new StringBuilder();
        VitalSigns latest = vitals.get(0);
        sb.append("Latest Vitals (").append(TIME_FMT.format(latest.getRecordedAt())).append("):\n");
        if (latest.getHeartRate() != null) sb.append("  HR: ").append(latest.getHeartRate()).append(" bpm\n");
        if (latest.getRespiratoryRate() != null) sb.append("  RR: ").append(latest.getRespiratoryRate()).append(" /min\n");
        if (latest.getSystolicBp() != null && latest.getDiastolicBp() != null) {
            sb.append("  BP: ").append(latest.getSystolicBp()).append("/").append(latest.getDiastolicBp()).append(" mmHg\n");
        }
        if (latest.getTemperature() != null) sb.append("  Temp: ").append(latest.getTemperature()).append(" °C\n");
        if (latest.getSpo2() != null) sb.append("  SpO2: ").append(latest.getSpo2()).append("%\n");
        if (latest.getAvpu() != null) sb.append("  AVPU: ").append(latest.getAvpu()).append("\n");
        if (latest.getBloodGlucose() != null) sb.append("  Blood Glucose: ").append(latest.getBloodGlucose()).append(" mmol/L\n");

        // Trend analysis
        if (vitals.size() >= 2) {
            sb.append("\nVital Signs Trend (").append(vitals.size()).append(" readings):\n");
            String hrTrend = computeVitalTrend(vitals.stream().map(VitalSigns::getHeartRate).collect(Collectors.toList()));
            String rrTrend = computeVitalTrend(vitals.stream().map(VitalSigns::getRespiratoryRate).collect(Collectors.toList()));
            String sbpTrend = computeVitalTrend(vitals.stream().map(VitalSigns::getSystolicBp).collect(Collectors.toList()));

            sb.append("  Heart Rate: ").append(hrTrend).append("\n");
            sb.append("  Respiratory Rate: ").append(rrTrend).append("\n");
            sb.append("  Systolic BP: ").append(sbpTrend).append("\n");
        }

        return sb.toString();
    }

    private String computeVitalTrend(List<Integer> values) {
        List<Integer> nonNull = values.stream()
                .filter(v -> v != null)
                .collect(Collectors.toList());
        if (nonNull.size() < 2) return "Insufficient data";

        // Compare first half average with second half average (most recent first, so reverse)
        int mid = nonNull.size() / 2;
        double recentAvg = nonNull.subList(0, mid).stream().mapToInt(Integer::intValue).average().orElse(0);
        double olderAvg = nonNull.subList(mid, nonNull.size()).stream().mapToInt(Integer::intValue).average().orElse(0);

        if (olderAvg == 0) return "Stable";

        double changePercent = ((recentAvg - olderAvg) / olderAvg) * 100;
        if (changePercent > 10) return "INCREASING (" + String.format("%.0f", recentAvg) + " avg, +" + String.format("%.1f", changePercent) + "%)";
        if (changePercent < -10) return "DECREASING (" + String.format("%.0f", recentAvg) + " avg, " + String.format("%.1f", changePercent) + "%)";
        return "Stable (" + String.format("%.0f", recentAvg) + " avg)";
    }

    private String buildInvestigationsResults(Visit visit) {
        List<Investigation> investigations = investigationRepository
                .findByVisitIdAndIsActiveTrueOrderByOrderedAtAsc(visit.getId());
        String standalone = buildStandaloneLabOrders(visit);

        if (investigations.isEmpty() && standalone.isEmpty()) {
            return "No investigations ordered.";
        }

        StringBuilder sb = new StringBuilder();
        if (!investigations.isEmpty()) {
            sb.append("Total Investigations: ").append(investigations.size()).append("\n\n");
            for (Investigation inv : investigations) {
                sb.append("  [").append(TIME_FMT.format(inv.getOrderedAt())).append("] ");
                sb.append(inv.getTestName());
                sb.append(" (").append(inv.getInvestigationType()).append(")");
                sb.append(" - Status: ").append(inv.getStatus());
                if (inv.getPriority() != null) {
                    sb.append(" | Priority: ").append(inv.getPriority());
                }
                if (inv.getOrderedByName() != null && !inv.getOrderedByName().isBlank()) {
                    sb.append(" | ordered by ").append(inv.getOrderedByName());
                }
                // Critical/abnormal flags render even when the result text is blank,
                // so a flagged-critical investigation never loses its marker.
                if (Boolean.TRUE.equals(inv.getIsCritical())) {
                    sb.append(" [CRITICAL]");
                } else if (Boolean.TRUE.equals(inv.getIsAbnormal())) {
                    sb.append(" [ABNORMAL]");
                }
                if (inv.getResult() != null && !inv.getResult().isBlank()) {
                    sb.append("\n    Result: ").append(inv.getResult());
                    if (inv.getResultedAt() != null) {
                        sb.append(" (resulted ").append(TIME_FMT.format(inv.getResultedAt())).append(")");
                    }
                } else if (inv.getResultedAt() != null) {
                    sb.append("\n    Resulted ").append(TIME_FMT.format(inv.getResultedAt()))
                            .append(" — result text pending entry");
                }
                sb.append("\n");
            }
        }

        if (!standalone.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Standalone Lab Orders:\n").append(standalone);
        }

        return sb.toString();
    }

    /**
     * Standalone lab orders (the enhanced lab workflow) NOT linked to an
     * Investigation row — otherwise wholly invisible to the handover. Tests
     * already mirrored on an Investigation are excluded to avoid double-counting.
     */
    private String buildStandaloneLabOrders(Visit visit) {
        List<LabOrder> orders = labOrderRepository
                .findByVisitIdAndInvestigationIsNullAndIsActiveTrueOrderByOrderedAtDesc(visit.getId());
        if (orders.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (LabOrder o : orders) {
            sb.append("  [").append(TIME_FMT.format(o.getOrderedAt())).append("] ");
            sb.append(o.getTestName());
            if (o.getPriority() != null) sb.append(" (").append(o.getPriority().name()).append(")");
            sb.append(" - Status: ").append(o.getStatus() != null ? o.getStatus().name() : "—");
            if (o.getOrderedByName() != null && !o.getOrderedByName().isBlank()) {
                sb.append(" | ordered by ").append(o.getOrderedByName());
            }
            if (o.isCritical()) sb.append(" [CRITICAL]");
            else if (o.isAbnormal()) sb.append(" [ABNORMAL]");
            if (o.getResultValue() != null && !o.getResultValue().isBlank()) {
                sb.append("\n    Result: ").append(o.getResultValue());
                if (o.getResultUnit() != null && !o.getResultUnit().isBlank()) {
                    sb.append(" ").append(o.getResultUnit());
                }
                if (o.getResultedAt() != null) {
                    sb.append(" (resulted ").append(TIME_FMT.format(o.getResultedAt())).append(")");
                }
            } else {
                sb.append(" — RESULT PENDING");
            }
            if (o.isCritical() && o.getCriticalValueAcknowledgedAt() == null) {
                sb.append("\n    ** CRITICAL VALUE NOT YET ACKNOWLEDGED **");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildDiagnosisSummary(Visit visit) {
        List<Diagnosis> diagnoses = diagnosisRepository
                .findByVisitIdAndIsActiveTrueOrderByDiagnosedAtAsc(visit.getId());

        if (diagnoses.isEmpty()) {
            return "No diagnoses recorded.";
        }

        StringBuilder sb = new StringBuilder();
        for (Diagnosis diag : diagnoses) {
            sb.append("  [").append(TIME_FMT.format(diag.getDiagnosedAt())).append("] ");
            sb.append(diag.getDiagnosisType()).append(": ");
            sb.append(diag.getDescription());
            if (diag.getIcdCode() != null) {
                sb.append(" (ICD-10: ").append(diag.getIcdCode()).append(")");
            }
            if (Boolean.TRUE.equals(diag.getIsPrimary())) {
                sb.append(" [PRIMARY]");
            }
            if (diag.getDiagnosedByName() != null) {
                sb.append(" - by ").append(diag.getDiagnosedByName());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildTreatmentSummary(Visit visit) {
        List<MedicationAdministration> medications = medicationAdministrationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visit.getId());

        if (medications.isEmpty()) {
            return "No medications administered.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Total Medications: ").append(medications.size()).append("\n\n");

        for (MedicationAdministration med : medications) {
            sb.append("  [").append(TIME_FMT.format(med.getPrescribedAt())).append("] ");
            sb.append(med.getDrugName());
            if (med.getDose() != null) sb.append(" ").append(med.getDose());
            sb.append(" via ").append(med.getRoute());
            sb.append(" - Status: ").append(med.getStatus());
            if (med.getAdministeredAt() != null) {
                sb.append(" | Administered: ").append(TIME_FMT.format(med.getAdministeredAt()));
                // Who gave it — the audit dose log carries this for typed orders, but
                // a legacy/free-text order otherwise loses the administering clinician.
                if (med.getAdministeredByName() != null && !med.getAdministeredByName().isBlank()) {
                    sb.append(" by ").append(med.getAdministeredByName());
                }
            }
            if (med.getPrescribedByName() != null) {
                sb.append(" | Prescribed by: ").append(med.getPrescribedByName());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildActiveClinicalAlerts(Visit visit) {
        Pageable all = PageRequest.of(0, 100);
        List<ClinicalAlert> alerts = clinicalAlertRepository
                .findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(visit.getId(), all)
                .getContent()
                .stream()
                .filter(a -> !a.isAcknowledged())
                .collect(Collectors.toList());

        if (alerts.isEmpty()) {
            return "No active clinical alerts.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Active Alerts: ").append(alerts.size()).append("\n\n");

        for (ClinicalAlert alert : alerts) {
            sb.append("  [").append(alert.getSeverity()).append("] ");
            sb.append(alert.getTitle());
            sb.append(" (").append(alert.getAlertType()).append(")");
            sb.append(" - ").append(TIME_FMT.format(alert.getCreatedAt()));
            if (alert.getMessage() != null) {
                sb.append("\n    ").append(alert.getMessage());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildOutstandingTasks(Visit visit) {
        StringBuilder sb = new StringBuilder();

        // Pending investigations
        List<Investigation> pendingInvestigations = investigationRepository
                .findByVisitIdAndStatusAndIsActiveTrueOrderByOrderedAtAsc(
                        visit.getId(), InvestigationStatus.ORDERED);
        List<Investigation> inProgressInvestigations = investigationRepository
                .findByVisitIdAndStatusAndIsActiveTrueOrderByOrderedAtAsc(
                        visit.getId(), InvestigationStatus.IN_PROGRESS);
        // A drawn-but-unresulted specimen is an outstanding task the next shift
        // must chase — previously it fell into no bucket and vanished from the list.
        List<Investigation> specimenCollected = investigationRepository
                .findByVisitIdAndStatusAndIsActiveTrueOrderByOrderedAtAsc(
                        visit.getId(), InvestigationStatus.SPECIMEN_COLLECTED);

        if (!pendingInvestigations.isEmpty()) {
            sb.append("Pending Investigations (").append(pendingInvestigations.size()).append("):\n");
            for (Investigation inv : pendingInvestigations) {
                sb.append("  - ").append(inv.getTestName())
                        .append(" (ordered ").append(TIME_FMT.format(inv.getOrderedAt())).append(")\n");
            }
        }

        if (!inProgressInvestigations.isEmpty()) {
            sb.append("In-Progress Investigations (").append(inProgressInvestigations.size()).append("):\n");
            for (Investigation inv : inProgressInvestigations) {
                sb.append("  - ").append(inv.getTestName())
                        .append(" (ordered ").append(TIME_FMT.format(inv.getOrderedAt())).append(")\n");
            }
        }

        if (!specimenCollected.isEmpty()) {
            sb.append("Specimen Collected — awaiting result (").append(specimenCollected.size()).append("):\n");
            for (Investigation inv : specimenCollected) {
                sb.append("  - ").append(inv.getTestName())
                        .append(" (ordered ").append(TIME_FMT.format(inv.getOrderedAt())).append(")\n");
            }
        }

        // Outstanding standalone lab orders (not yet resulted).
        List<LabOrder> outstandingLabs = labOrderRepository
                .findByVisitIdAndInvestigationIsNullAndIsActiveTrueOrderByOrderedAtDesc(visit.getId())
                .stream()
                .filter(o -> o.getResultedAt() == null && o.getCancelledAt() == null)
                .collect(Collectors.toList());
        if (!outstandingLabs.isEmpty()) {
            sb.append("Outstanding Lab Orders (").append(outstandingLabs.size()).append("):\n");
            for (LabOrder o : outstandingLabs) {
                sb.append("  - ").append(o.getTestName());
                if (o.getPriority() != null) sb.append(" (").append(o.getPriority().name()).append(")");
                sb.append(" — ").append(o.getStatus() != null ? o.getStatus().name() : "ordered").append("\n");
            }
        }

        // Pending medications
        List<MedicationAdministration> medications = medicationAdministrationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visit.getId());
        List<MedicationAdministration> pendingMeds = medications.stream()
                .filter(m -> m.getStatus() == MedicationStatus.PRESCRIBED)
                .collect(Collectors.toList());

        if (!pendingMeds.isEmpty()) {
            sb.append("Pending Medications (").append(pendingMeds.size()).append("):\n");
            for (MedicationAdministration med : pendingMeds) {
                sb.append("  - ").append(med.getDrugName());
                if (med.getDose() != null) sb.append(" ").append(med.getDose());
                sb.append(" via ").append(med.getRoute()).append("\n");
            }
        }

        // Disposition & follow-up status — unresolved items the next clinician owns.
        StringBuilder dispo = new StringBuilder();
        if (visit.getDispositionType() != null) {
            dispo.append("Disposition: ").append(visit.getDispositionType());
            if (visit.getDispositionNotes() != null && !visit.getDispositionNotes().isBlank()) {
                dispo.append(" — ").append(visit.getDispositionNotes());
            }
            dispo.append("\n");
        } else if (visit.getStatus() == VisitStatus.PENDING_DISPOSITION) {
            dispo.append("Disposition: PENDING — decision required.\n");
        }
        if (visit.isPendingResusOverflow()) {
            dispo.append("** RESUS OVERFLOW — patient at RESUS acuity without an available bed; needs placement. **\n");
        }
        if (visit.isAmbulancePreArrival() && visit.getArrivalConfirmedAt() == null) {
            dispo.append("Ambulance pre-arrival — not yet physically confirmed at the door");
            if (visit.getFieldTriageCategory() != null) {
                dispo.append(" (field triage ").append(visit.getFieldTriageCategory()).append(")");
            }
            dispo.append("\n");
        }
        if (visit.getEdRetriageDueAt() != null && visit.getEdRetriageDueAt().isBefore(Instant.now())) {
            dispo.append("** ED re-triage OVERDUE for this ambulance arrival — confirm the field triage still holds. **\n");
        }
        if (dispo.length() > 0) {
            sb.append("\nDisposition & Follow-up:\n").append(dispo);
        }

        return sb.length() > 0 ? sb.toString() : "No outstanding tasks.";
    }

    private String buildPlanOfCare(Visit visit) {
        StringBuilder sb = new StringBuilder();

        // Doctor of record — accountability for the continuing team.
        var doctor = visit.getPrimaryClinician();
        if (doctor != null) {
            sb.append("Doctor of Record: ").append(doctor.getFirstName()).append(" ")
              .append(doctor.getLastName()).append("\n\n");
        }

        // The outgoing clinician's narrative impression/assessment — the
        // "what does the doctor think right now" that a plan alone omits.
        // Most recent of each note type, examination → impression → progress.
        appendLatestNote(sb, visit, NoteType.PHYSICAL_FINDINGS, "Examination Findings");
        appendLatestNote(sb, visit, NoteType.DOCTOR_NOTE, "Clinical Impression (Doctor's Note)");
        appendLatestNote(sb, visit, NoteType.PROGRESS_NOTE, "Latest Progress Note");

        // Treatment plan.
        clinicalNoteRepository
                .findFirstByVisitIdAndNoteTypeAndIsActiveTrueOrderByRecordedAtDesc(
                        visit.getId(), NoteType.TREATMENT_PLAN)
                .ifPresent(note -> {
                    sb.append("Treatment Plan (").append(TIME_FMT.format(note.getRecordedAt())).append(")");
                    if (note.getRecordedByName() != null) sb.append(" — ").append(note.getRecordedByName());
                    sb.append(":\n").append(note.getContent()).append("\n");
                });

        return sb.length() > 0 ? sb.toString() : "No assessment or plan recorded.";
    }

    /** Append the most recent note of {@code type} under {@code heading}, if one exists. */
    private void appendLatestNote(StringBuilder sb, Visit visit, NoteType type, String heading) {
        clinicalNoteRepository
                .findFirstByVisitIdAndNoteTypeAndIsActiveTrueOrderByRecordedAtDesc(visit.getId(), type)
                .ifPresent(note -> {
                    sb.append(heading).append(" (").append(TIME_FMT.format(note.getRecordedAt())).append(")");
                    if (note.getRecordedByName() != null) sb.append(" — ").append(note.getRecordedByName());
                    sb.append(":\n").append(note.getContent()).append("\n\n");
                });
    }

    private String buildEdTimeline(Visit visit) {
        StringBuilder sb = new StringBuilder();
        sb.append("ED Timeline:\n");

        sb.append("  Arrival: ").append(TIME_FMT.format(visit.getArrivalTime())).append("\n");

        if (visit.getTriageTime() != null) {
            long doorToTriageMin = Duration.between(visit.getArrivalTime(), visit.getTriageTime()).toMinutes();
            sb.append("  Triage: ").append(TIME_FMT.format(visit.getTriageTime()));
            sb.append(" (Door-to-Triage: ").append(doorToTriageMin).append(" min)\n");
        }

        if (visit.getAssessmentStartTime() != null) {
            long doorToPhysician = Duration.between(visit.getArrivalTime(), visit.getAssessmentStartTime()).toMinutes();
            sb.append("  Assessment Start: ").append(TIME_FMT.format(visit.getAssessmentStartTime()));
            sb.append(" (Door-to-Physician: ").append(doorToPhysician).append(" min)\n");
        }

        if (visit.getDispositionTime() != null) {
            long totalStay = Duration.between(visit.getArrivalTime(), visit.getDispositionTime()).toMinutes();
            sb.append("  Disposition: ").append(TIME_FMT.format(visit.getDispositionTime()));
            if (visit.getDispositionType() != null) {
                sb.append(" [").append(visit.getDispositionType()).append("]");
            }
            sb.append(" (Total ED Stay: ").append(totalStay).append(" min)\n");
        }

        sb.append("  Current Status: ").append(visit.getStatus()).append("\n");

        if (visit.getCurrentTriageCategory() != null) {
            sb.append("  Current Triage Category: ").append(visit.getCurrentTriageCategory()).append("\n");
        }

        // Zone-movement trail — where the patient has been moved and why.
        List<ZoneTransfer> transfers = zoneTransferRepository
                .findByVisitIdAndIsActiveTrueOrderByInitiatedAtAsc(visit.getId());
        if (!transfers.isEmpty()) {
            sb.append("\nZone Movements:\n");
            for (ZoneTransfer t : transfers) {
                sb.append("  [").append(TIME_FMT.format(t.getInitiatedAt())).append("] ");
                sb.append(t.getFromZone() != null ? t.getFromZone().getLabel() : "(unplaced)");
                sb.append(" → ").append(t.getToZone() != null ? t.getToZone().getLabel() : "—");
                if (t.getStatus() != null) sb.append(" [").append(prettyEnum(t.getStatus())).append("]");
                if (t.getReason() != null && !t.getReason().isBlank()) {
                    sb.append(" — ").append(t.getReason());
                }
                sb.append(" (by ").append(t.getInitiatedBy() != null
                        ? userName(t.getInitiatedBy()) : "System auto re-triage").append(")");
                if (t.getAcceptedBy() != null) {
                    sb.append("; accepted by ").append(userName(t.getAcceptedBy()));
                    if (t.getAcceptedAt() != null) sb.append(" ").append(TIME_FMT.format(t.getAcceptedAt()));
                }
                if (t.getDeclinedBy() != null) {
                    sb.append("; declined by ").append(userName(t.getDeclinedBy()));
                    if (t.getDeclinedReason() != null && !t.getDeclinedReason().isBlank()) {
                        sb.append(" (").append(t.getDeclinedReason()).append(")");
                    }
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    // ====================================================================
    // V73 SECTION BUILDERS — previously-omitted clinical domains
    // ====================================================================

    /**
     * Pre-hospital / EMS run snapshot: MIST handover, engine field triage,
     * field vitals, transport status, the transfer-of-care chain, and the
     * pre-hospital interventions given. Returns null when the patient did not
     * arrive by ambulance (no active EMS run), so the section is omitted.
     */
    private String buildPrehospitalSummary(Visit visit) {
        Optional<EmsRun> runOpt = emsRunRepository.findByVisitIdAndIsActiveTrue(visit.getId());
        if (runOpt.isEmpty()) return null;
        EmsRun run = runOpt.get();
        StringBuilder sb = new StringBuilder();

        // Who brought the patient in.
        StringBuilder svc = new StringBuilder();
        if (run.getService() != null) svc.append(run.getService().name());
        if (run.getUnitCallsign() != null && !run.getUnitCallsign().isBlank()) {
            svc.append(svc.length() > 0 ? " " : "").append(run.getUnitCallsign());
        }
        if (svc.length() > 0) sb.append("Service: ").append(svc).append("\n");
        if (run.getParamedicName() != null && !run.getParamedicName().isBlank()) {
            sb.append("Paramedic: ").append(run.getParamedicName()).append("\n");
        }
        if (run.getIncidentLocation() != null && !run.getIncidentLocation().isBlank()) {
            sb.append("Incident location: ").append(run.getIncidentLocation()).append("\n");
        }
        if (run.isLightsActive()) {
            sb.append("** BLUE-LIGHT / PRIORITY TRANSPORT **");
            if (run.getLightsActivatedAt() != null) {
                sb.append(" (since ").append(TIME_FMT.format(run.getLightsActivatedAt())).append(")");
            }
            sb.append("\n");
        }

        // MIST.
        appendField(sb, "Mechanism (M)", run.getMechanism());
        appendField(sb, "Injuries observed (I)", run.getInjuriesObserved());
        appendField(sb, "History / on-scene (H)", run.getHistorySummary());

        // Field triage (engine-computed in the field).
        if (run.getFieldTriageCategory() != null && !run.getFieldTriageCategory().isBlank()) {
            sb.append("Field triage: ").append(run.getFieldTriageCategory());
            if (run.getFieldTewsScore() != null) sb.append(" | field TEWS ").append(run.getFieldTewsScore());
            if (Boolean.TRUE.equals(run.getFieldTriageIsChild())) sb.append(" | pediatric (KFH)");
            sb.append("\n");
            appendField(sb, "  Field triage rationale", run.getFieldTriageReason());
            appendField(sb, "  Field triage decision path", run.getFieldTriageDecisionPath());
        }

        // Field vitals snapshot.
        StringBuilder v = new StringBuilder();
        appendInline(v, "GCS", run.getFieldGcs());
        appendInline(v, "RR", run.getFieldRespRate());
        appendInline(v, "HR", run.getFieldHr());
        if (run.getFieldSbp() != null) {
            v.append(v.length() > 0 ? ", " : "").append("BP ").append(run.getFieldSbp());
            if (run.getFieldDbp() != null) v.append("/").append(run.getFieldDbp());
        }
        appendInline(v, "SpO2", run.getFieldSpo2());
        if (run.getFieldTemp() != null) v.append(v.length() > 0 ? ", " : "").append("Temp ").append(run.getFieldTemp());
        if (run.getFieldGlucose() != null) v.append(v.length() > 0 ? ", " : "").append("Glucose ").append(run.getFieldGlucose());
        if (v.length() > 0) sb.append("Field vitals: ").append(v).append("\n");

        // Run timeline.
        StringBuilder t = new StringBuilder();
        if (run.getDispatchedAt() != null) t.append("dispatched ").append(TIME_FMT.format(run.getDispatchedAt()));
        if (run.getSceneArrivedAt() != null) t.append(t.length() > 0 ? ", " : "").append("on-scene ").append(TIME_FMT.format(run.getSceneArrivedAt()));
        if (run.getSceneLeftAt() != null) t.append(t.length() > 0 ? ", " : "").append("left ").append(TIME_FMT.format(run.getSceneLeftAt()));
        if (run.getEdArrivedAt() != null) t.append(t.length() > 0 ? ", " : "").append("ED arrival ").append(TIME_FMT.format(run.getEdArrivedAt()));
        if (t.length() > 0) sb.append("Run timeline: ").append(t).append("\n");
        sb.append("Run status: ").append(run.getStatus() != null ? run.getStatus().name() : "—").append("\n");

        // Transfer-of-care chain.
        if (run.getPreArrivalAckedByName() != null && !run.getPreArrivalAckedByName().isBlank()) {
            sb.append("Pre-arrival alert acknowledged by ").append(run.getPreArrivalAckedByName());
            if (run.getPreArrivalAckedAt() != null) sb.append(" at ").append(TIME_FMT.format(run.getPreArrivalAckedAt()));
            sb.append("\n");
        }
        if (run.getHandedOffToName() != null && !run.getHandedOffToName().isBlank()) {
            sb.append("Transfer of care to ").append(run.getHandedOffToName());
            if (run.getHandedOffAt() != null) sb.append(" at ").append(TIME_FMT.format(run.getHandedOffAt()));
            sb.append("\n");
        }
        appendField(sb, "Handover acknowledgement", run.getHandoverAcknowledgementText());
        appendField(sb, "Run notes", run.getNotes());

        // Pre-hospital interventions (MIST 'T').
        List<EmsIntervention> interventions = emsInterventionRepository
                .findByEmsRunIdAndIsActiveTrueOrderByGivenAtAsc(run.getId());
        if (!interventions.isEmpty()) {
            sb.append("\nPre-hospital interventions:\n");
            for (EmsIntervention iv : interventions) {
                sb.append("  [").append(iv.getGivenAt() != null ? TIME_FMT.format(iv.getGivenAt()) : "—").append("] ");
                sb.append(iv.getType() != null ? iv.getType().getDescription() : "Intervention");
                if (iv.getDetail() != null && !iv.getDetail().isBlank()) sb.append(" — ").append(iv.getDetail());
                if (iv.getDose() != null && !iv.getDose().isBlank()) sb.append(" ").append(iv.getDose());
                if (iv.getRoute() != null && !iv.getRoute().isBlank()) sb.append(" ").append(iv.getRoute());
                if (iv.getOutcome() != null && !iv.getOutcome().isBlank()) sb.append(" → ").append(iv.getOutcome());
                if (iv.getGivenByName() != null && !iv.getGivenByName().isBlank()) sb.append(" (by ").append(iv.getGivenByName()).append(")");
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Active time-critical protocols and acute events at generation time:
     * fast-track (stroke/STEMI), sepsis screening + 1-hour bundle, ICU
     * escalation, hypoglycaemia events, red-flag clinical-sign trajectory, and
     * care-pathway activations. Returns null when none are present.
     */
    private String buildAcuteProtocols(Visit visit) {
        StringBuilder sb = new StringBuilder();
        appendBlock(sb, buildFastTrackBlock(visit));
        appendBlock(sb, buildSepsisBlock(visit));
        appendBlock(sb, buildIcuBlock(visit));
        appendBlock(sb, buildHypoglycemiaBlock(visit));
        appendBlock(sb, buildClinicalSignsBlock(visit));
        appendBlock(sb, buildPathwayBlock(visit));
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String buildFastTrackBlock(Visit visit) {
        Optional<FastTrackActivation> ftOpt = fastTrackActivationRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(visit.getId());
        if (ftOpt.isEmpty()) return null;
        FastTrackActivation ft = ftOpt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("FAST-TRACK: ").append(prettyEnum(ft.getFastTrackType()));
        sb.append(" [").append(prettyEnum(ft.getStatus())).append("]");
        if (ft.getActivatedAt() != null) sb.append(" — activated ").append(TIME_FMT.format(ft.getActivatedAt()));
        if (ft.getActivatedByName() != null && !ft.getActivatedByName().isBlank()) sb.append(" by ").append(ft.getActivatedByName());
        sb.append("\n");
        if (ft.getSymptomOnsetTime() != null) sb.append("  Symptom onset: ").append(TIME_FMT.format(ft.getSymptomOnsetTime())).append(" (thrombolysis window)\n");
        if (ft.getChestPainOnsetTime() != null) sb.append("  Chest-pain onset: ").append(TIME_FMT.format(ft.getChestPainOnsetTime())).append("\n");
        appendField(sb, "  BE-FAST", ft.getBeFastScore());
        if (ft.getNihssScore() != null) sb.append("  NIHSS: ").append(ft.getNihssScore()).append("\n");
        appendYesNo(sb, "  ST elevation", ft.getStElevation());
        if (ft.getTroponinResult() != null) {
            sb.append("  Troponin: ").append(ft.getTroponinResult());
            if (ft.getTroponinResultedAt() != null) sb.append(" (").append(TIME_FMT.format(ft.getTroponinResultedAt())).append(")");
            sb.append("\n");
        }
        appendYesNo(sb, "  Hemorrhagic", ft.getIsHemorrhagic());
        appendField(sb, "  CT result", ft.getCtResult());
        appendField(sb, "  ECG result", ft.getEcgResult());
        appendYesNo(sb, "  Thrombolysis eligible", ft.getThrombolysisEligible());
        if (ft.getThrombolysisStartedAt() != null) sb.append("  Thrombolysis started: ").append(TIME_FMT.format(ft.getThrombolysisStartedAt())).append("\n");
        appendYesNo(sb, "  Aspirin given", ft.getAspirinGiven());
        appendYesNo(sb, "  Referred for PCI", ft.getReferredForPci());
        if (ft.getDoorToCtMinutes() != null) sb.append("  Door-to-CT: ").append(ft.getDoorToCtMinutes()).append(" min\n");
        if (ft.getDoorToEcgMinutes() != null) sb.append("  Door-to-ECG: ").append(ft.getDoorToEcgMinutes()).append(" min\n");
        if (ft.getDoorToNeedleMinutes() != null) sb.append("  Door-to-needle: ").append(ft.getDoorToNeedleMinutes()).append(" min\n");
        appendField(sb, "  Outcome", ft.getOutcome());
        appendField(sb, "  Notes", ft.getNotes());
        return sb.toString();
    }

    private String buildSepsisBlock(Visit visit) {
        Optional<SepsisScreening> sOpt = sepsisScreeningRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(visit.getId());
        if (sOpt.isEmpty()) return null;
        SepsisScreening s = sOpt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("SEPSIS: ").append(prettyEnum(s.getSepsisStatus()));
        sb.append(" — qSOFA ").append(s.getQsofaScore()).append(", SIRS ").append(s.getSirsScore());
        if (s.getLactateLevel() != null) sb.append(", lactate ").append(s.getLactateLevel()).append(" mmol/L");
        sb.append("\n");
        appendField(sb, "  Suspected source", s.getSuspectedInfectionSource());
        StringBuilder bundle = new StringBuilder();
        if (s.getBundleStartedAt() != null) bundle.append("started ").append(TIME_FMT.format(s.getBundleStartedAt()));
        else bundle.append("NOT STARTED");
        if (s.getBundleCompletedAt() != null) bundle.append(", completed ").append(TIME_FMT.format(s.getBundleCompletedAt()));
        sb.append("  1-hour bundle: ").append(bundle).append("\n");
        sb.append("    blood culture: ").append(yn(s.isBloodCultureObtained()))
          .append(" | antibiotics: ").append(yn(s.isBroadSpectrumAntibiotics()))
          .append(" | IV crystalloid: ").append(yn(s.isIvCrystalloidBolus()))
          .append(" | lactate: ").append(yn(s.isLactateMeasured()))
          .append(" | vasopressors: ").append(yn(s.isVasopressorsIfNeeded()))
          .append(" | repeat lactate: ").append(yn(s.isRepeatLactateIfElevated())).append("\n");
        if (s.getScreenedByName() != null && !s.getScreenedByName().isBlank()) {
            sb.append("  Screened by ").append(s.getScreenedByName());
            if (s.getScreenedAt() != null) sb.append(" ").append(TIME_FMT.format(s.getScreenedAt()));
            sb.append("\n");
        }
        appendField(sb, "  Notes", s.getNotes());
        return sb.toString();
    }

    private String buildIcuBlock(Visit visit) {
        Optional<IcuEscalation> iOpt = icuEscalationRepository.findByVisitIdAndIsActiveTrue(visit.getId());
        if (iOpt.isEmpty()) return null;
        IcuEscalation i = iOpt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("ICU ESCALATION: ").append(prettyEnum(i.getStatus()));
        sb.append(i.isAutomatic() ? " (auto-detected)" : " (clinician-initiated)").append("\n");
        appendField(sb, "  Reason", i.getEscalationReason());
        if (i.getTriggerType() != null) sb.append("  Trigger: ").append(prettyEnum(i.getTriggerType())).append("\n");
        appendYesNo(sb, "  Intubation required", i.getIntubationRequired());
        appendYesNo(sb, "  Vasopressors required", i.getVasopressorsRequired());
        appendYesNo(sb, "  Mechanical ventilation", i.getMechanicalVentilation());
        appendYesNo(sb, "  ICU bed available", i.getIcuBedAvailable());
        appendField(sb, "  ICU bed", i.getIcuBedNumber());
        if (i.getEscalatedByName() != null && !i.getEscalatedByName().isBlank()) {
            sb.append("  Requested by ").append(i.getEscalatedByName());
            if (i.getEscalatedAt() != null) sb.append(" ").append(TIME_FMT.format(i.getEscalatedAt()));
            sb.append("\n");
        }
        if (i.getIcuConsultant() != null && !i.getIcuConsultant().isBlank()) {
            sb.append("  ICU consultant: ").append(i.getIcuConsultant());
            if (i.getIcuRespondedAt() != null) sb.append(", responded ").append(TIME_FMT.format(i.getIcuRespondedAt()));
            sb.append("\n");
        }
        if (i.getTransferredAt() != null) sb.append("  Transferred to ICU: ").append(TIME_FMT.format(i.getTransferredAt())).append("\n");
        appendField(sb, "  Decline reason", i.getDeclineReason());
        appendField(sb, "  Alternative plan", i.getAlternativePlan());
        appendField(sb, "  Stabilization notes", i.getStabilizationNotes());
        appendField(sb, "  Outcome", i.getOutcome());
        return sb.toString();
    }

    private String buildHypoglycemiaBlock(Visit visit) {
        List<HypoglycemiaEvent> events = hypoglycemiaEventRepository
                .findByVisitIdAndIsActiveTrueOrderByDetectedAtDesc(visit.getId());
        if (events.isEmpty()) return null;
        // All unresolved + the single most-recent resolved (list is newest-first).
        List<HypoglycemiaEvent> show = new ArrayList<>();
        boolean resolvedAdded = false;
        for (HypoglycemiaEvent e : events) {
            if (!e.isResolved()) show.add(e);
            else if (!resolvedAdded) { show.add(e); resolvedAdded = true; }
        }
        if (show.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("HYPOGLYCAEMIA EVENTS (").append(show.size()).append("):\n");
        for (HypoglycemiaEvent e : show) {
            sb.append("  [").append(e.getDetectedAt() != null ? TIME_FMT.format(e.getDetectedAt()) : "—").append("] ");
            if (e.getSeverity() != null) sb.append(e.getSeverity()).append(" — ");
            if (e.getGlucoseLevel() != null) sb.append("glucose ").append(e.getGlucoseLevel()).append(" mmol/L");
            sb.append(e.isResolved() ? " [RESOLVED" : " [UNRESOLVED");
            if (e.isResolved() && e.getResolvedAt() != null) sb.append(" ").append(TIME_FMT.format(e.getResolvedAt()));
            sb.append("]");
            if (e.getTreatmentGiven() != null && !e.getTreatmentGiven().isBlank()) {
                sb.append("\n    Treatment: ").append(e.getTreatmentGiven());
                if (e.getTreatmentGivenByName() != null && !e.getTreatmentGivenByName().isBlank()) sb.append(" by ").append(e.getTreatmentGivenByName());
                if (e.getTreatmentGivenAt() != null) sb.append(" at ").append(TIME_FMT.format(e.getTreatmentGivenAt()));
            }
            if (e.getRepeatGlucoseLevel() != null) {
                sb.append("\n    Repeat glucose: ").append(e.getRepeatGlucoseLevel()).append(" mmol/L");
                if (e.getRepeatGlucoseAt() != null) sb.append(" at ").append(TIME_FMT.format(e.getRepeatGlucoseAt()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildClinicalSignsBlock(Visit visit) {
        List<ClinicalSignEvent> current = clinicalSignEventRepository.findCurrentStateForVisit(visit.getId());
        List<ClinicalSignEvent> active = current.stream()
                .filter(e -> e.getStatus() == ClinicalSignStatus.PRESENT || e.getStatus() == ClinicalSignStatus.WORSENING)
                .collect(Collectors.toList());
        if (active.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("ACTIVE RED-FLAG SIGNS (").append(active.size()).append("):\n");
        for (ClinicalSignEvent e : active) {
            sb.append("  - ");
            if (e.getStatus() == ClinicalSignStatus.WORSENING) sb.append("** WORSENING ** ");
            sb.append(prettyCode(e.getSignCode()));
            if (e.getSignCategory() != null) sb.append(" [").append(prettyEnum(e.getSignCategory())).append("]");
            sb.append(" — ").append(e.getStatus().name());
            if (e.getNumericValue() != null) sb.append(" (").append(e.getNumericValue()).append(")");
            if (e.getRecordedAt() != null) sb.append(", ").append(TIME_FMT.format(e.getRecordedAt()));
            if (e.getRecordedByName() != null && !e.getRecordedByName().isBlank()) sb.append(" by ").append(e.getRecordedByName());
            if (e.isBaseline()) sb.append(" (baseline at triage)");
            if (e.getNotes() != null && !e.getNotes().isBlank()) sb.append("\n      ").append(e.getNotes());
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildPathwayBlock(Visit visit) {
        List<PathwayActivation> activations = pathwayActivationRepository
                .findByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(visit.getId());
        if (activations.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("CARE PATHWAYS (").append(activations.size()).append("):\n");
        for (PathwayActivation p : activations) {
            sb.append("  - ");
            sb.append(p.getPathway() != null ? p.getPathway().getPathwayName() : "Pathway");
            sb.append(" [").append(prettyEnum(p.getStatus())).append("]");
            if (p.getActivatedAt() != null) sb.append(", activated ").append(TIME_FMT.format(p.getActivatedAt()));
            if (p.getActivatedByName() != null && !p.getActivatedByName().isBlank()) sb.append(" by ").append(p.getActivatedByName());
            if (p.getCompletedAt() != null) sb.append(", completed ").append(TIME_FMT.format(p.getCompletedAt()));
            if (p.getDeviationReason() != null && !p.getDeviationReason().isBlank()) sb.append("\n      Deviation: ").append(p.getDeviationReason());
            if (p.getNotes() != null && !p.getNotes().isBlank()) sb.append("\n      Notes: ").append(p.getNotes());
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Formal clinical documents on the visit (procedure / operative /
     * consultation-referral / consent / AMA / nursing-assessment notes) as an
     * index with author + signed status. Returns null when none exist.
     */
    private String buildProceduresDocuments(Visit visit) {
        List<ClinicalDocument> docs = clinicalDocumentRepository
                .findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(visit.getId(), Pageable.unpaged())
                .getContent();
        if (docs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("Documents (").append(docs.size()).append(", newest first):\n");
        for (ClinicalDocument d : docs) {
            sb.append("  [").append(d.getCreatedAt() != null ? TIME_FMT.format(d.getCreatedAt()) : "—").append("] ");
            sb.append(prettyEnum(d.getDocumentType())).append(": ").append(d.getTitle());
            sb.append(d.isSigned() ? " [SIGNED" : " [DRAFT");
            if (d.isSigned() && d.getSignedAt() != null) sb.append(" ").append(TIME_FMT.format(d.getSignedAt()));
            sb.append("]");
            if (d.isAmendment()) sb.append(" [AMENDMENT]");
            if (d.getAuthorName() != null && !d.getAuthorName().isBlank()) {
                sb.append(" — ").append(d.getAuthorName());
                if (d.getAuthorRole() != null && !d.getAuthorRole().isBlank()) sb.append(" (").append(d.getAuthorRole()).append(")");
            }
            if (d.getCoSignedByName() != null && !d.getCoSignedByName().isBlank()) {
                sb.append("; co-signed by ").append(d.getCoSignedByName());
                if (d.getCoSignedAt() != null) sb.append(" ").append(TIME_FMT.format(d.getCoSignedAt()));
            }
            if (d.isAmendment() && d.getAmendmentReason() != null && !d.getAmendmentReason().isBlank()) {
                sb.append("\n      Amendment reason: ").append(d.getAmendmentReason());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── Render helpers ──────────────────────────────────────────────

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append(label).append(": ").append(value).append("\n");
    }

    private static void appendInline(StringBuilder sb, String label, Integer value) {
        if (value != null) sb.append(sb.length() > 0 ? ", " : "").append(label).append(" ").append(value);
    }

    private static void appendYesNo(StringBuilder sb, String label, Boolean value) {
        if (value != null) sb.append(label).append(": ").append(value ? "Yes" : "No").append("\n");
    }

    private static void appendBlock(StringBuilder sb, String block) {
        if (block != null && !block.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(block);
        }
    }

    private static String yn(boolean b) {
        return b ? "yes" : "no";
    }

    private static String prettyEnum(Enum<?> e) {
        return e == null ? "—" : e.name().replace('_', ' ');
    }

    private static String prettyCode(String code) {
        return code == null ? "—" : code.replace('_', ' ');
    }

    private static String userName(User u) {
        if (u == null) return "—";
        String name = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                + (u.getLastName() != null ? u.getLastName() : "")).trim();
        return name.isEmpty() ? "—" : name;
    }
}
