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
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
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

        if (patient.getChronicConditions() != null && !patient.getChronicConditions().isBlank()) {
            sb.append("Known Conditions: ").append(patient.getChronicConditions()).append("\n");
        }

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
            if (record.getDecisionPath() != null) {
                sb.append("\n    Decision: ").append(record.getDecisionPath());
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

        if (investigations.isEmpty()) {
            return "No investigations ordered.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Total Investigations: ").append(investigations.size()).append("\n\n");

        for (Investigation inv : investigations) {
            sb.append("  [").append(TIME_FMT.format(inv.getOrderedAt())).append("] ");
            sb.append(inv.getTestName());
            sb.append(" (").append(inv.getInvestigationType()).append(")");
            sb.append(" - Status: ").append(inv.getStatus());
            if (inv.getPriority() != null) {
                sb.append(" | Priority: ").append(inv.getPriority());
            }
            if (inv.getResult() != null && !inv.getResult().isBlank()) {
                sb.append("\n    Result: ").append(inv.getResult());
                if (Boolean.TRUE.equals(inv.getIsCritical())) {
                    sb.append(" [CRITICAL]");
                } else if (Boolean.TRUE.equals(inv.getIsAbnormal())) {
                    sb.append(" [ABNORMAL]");
                }
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

        return sb.toString();
    }
}
