package com.smartTriage.smartTriage_server.module.safety.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.safety.dto.*;
import com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident;
import com.smartTriage.smartTriage_server.module.safety.repository.SafetyIncidentRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SafetyIncidentService — manages patient safety incident reporting, investigation,
 * and corrective action workflows.
 *
 * Aligned with Rwanda's patient safety and quality improvement frameworks.
 * Auto-generates CRITICAL alerts for SEVERE_HARM and DEATH incidents.
 * Supports anonymous reporting to encourage safety culture.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SafetyIncidentService {

    private final SafetyIncidentRepository incidentRepository;
    private final HospitalRepository hospitalRepository;
    private final VisitRepository visitRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final SafetyIncidentPdfService safetyIncidentPdfService;

    private static final DateTimeFormatter DATE_PREFIX_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withZone(ZoneId.of("Africa/Kigali"));

    /**
     * Report a new patient safety incident.
     * Auto-generates incident number in format SI-YYYYMMDD-XXXXX.
     * Generates CRITICAL alert for SEVERE_HARM or DEATH severity.
     */
    @Transactional
    public SafetyIncident reportIncident(ReportIncidentRequest request) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", request.getHospitalId()));

        Visit visit = null;
        if (request.getVisitId() != null) {
            visit = visitRepository.findByIdAndIsActiveTrue(request.getVisitId())
                    .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", request.getVisitId()));
        }

        Instant now = Instant.now();
        String incidentNumber = generateIncidentNumber(now);

        SafetyIncident incident = SafetyIncident.builder()
                .hospital(hospital)
                .visit(visit)
                .incidentNumber(incidentNumber)
                .incidentType(request.getIncidentType())
                .severity(request.getSeverity())
                .status(IncidentStatus.REPORTED)
                .incidentDateTime(request.getIncidentDateTime())
                .locationInHospital(request.getLocationInHospital())
                .description(request.getDescription())
                .contributingFactors(request.getContributingFactors())
                .immediateActions(request.getImmediateActions())
                .reportedByName(request.getReportedByName())
                .reportedByRole(request.getReportedByRole())
                .reportedAt(now)
                .involvedStaffNames(request.getInvolvedStaffNames())
                .patientHarmed(request.getPatientHarmed())
                .isAnonymous(request.isAnonymous())
                .notes(request.getNotes())
                .build();

        incident = incidentRepository.save(incident);

        // Generate CRITICAL alert for severe incidents
        if (request.getSeverity() == IncidentSeverity.SEVERE_HARM
                || request.getSeverity() == IncidentSeverity.DEATH) {
            generateCriticalIncidentAlert(incident, visit);
        }

        log.info("Safety incident reported: number={}, type={}, severity={}, hospital={}",
                incidentNumber, request.getIncidentType(), request.getSeverity(), hospital.getName());

        return incident;
    }

    /**
     * Update incident details.
     */
    @Transactional
    public SafetyIncident updateIncident(UUID incidentId, UpdateIncidentRequest request) {
        SafetyIncident incident = findActiveIncident(incidentId);

        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw new IllegalStateException("Cannot update a closed incident");
        }

        if (request.getIncidentType() != null) {
            incident.setIncidentType(request.getIncidentType());
        }
        if (request.getSeverity() != null) {
            incident.setSeverity(request.getSeverity());
        }
        if (request.getIncidentDateTime() != null) {
            incident.setIncidentDateTime(request.getIncidentDateTime());
        }
        if (request.getLocationInHospital() != null) {
            incident.setLocationInHospital(request.getLocationInHospital());
        }
        if (request.getDescription() != null) {
            incident.setDescription(request.getDescription());
        }
        if (request.getContributingFactors() != null) {
            incident.setContributingFactors(request.getContributingFactors());
        }
        if (request.getImmediateActions() != null) {
            incident.setImmediateActions(request.getImmediateActions());
        }
        if (request.getInvolvedStaffNames() != null) {
            incident.setInvolvedStaffNames(request.getInvolvedStaffNames());
        }
        if (request.getPatientHarmed() != null) {
            incident.setPatientHarmed(request.getPatientHarmed());
        }
        if (request.getNotes() != null) {
            incident.setNotes(request.getNotes());
        }

        // If severity was updated to SEVERE_HARM or DEATH, generate alert
        if (request.getSeverity() != null
                && (request.getSeverity() == IncidentSeverity.SEVERE_HARM
                || request.getSeverity() == IncidentSeverity.DEATH)) {
            generateCriticalIncidentAlert(incident, incident.getVisit());
        }

        incident = incidentRepository.save(incident);
        log.info("Safety incident updated: id={}, number={}", incidentId, incident.getIncidentNumber());
        return incident;
    }

    /**
     * Start investigation for an incident.
     */
    @Transactional
    public SafetyIncident startInvestigation(UUID incidentId, String investigatorName) {
        SafetyIncident incident = findActiveIncident(incidentId);

        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw new IllegalStateException("Cannot investigate a closed incident");
        }

        incident.setStatus(IncidentStatus.INVESTIGATION_STARTED);
        incident.setInvestigatorName(investigatorName);
        incident.setInvestigationStartedAt(Instant.now());

        incident = incidentRepository.save(incident);
        log.info("Investigation started for incident: number={}, investigator={}",
                incident.getIncidentNumber(), investigatorName);
        return incident;
    }

    /**
     * Record root cause analysis results.
     */
    @Transactional
    public SafetyIncident recordRootCause(UUID incidentId, RootCauseRequest request) {
        SafetyIncident incident = findActiveIncident(incidentId);

        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw new IllegalStateException("Cannot update root cause for a closed incident");
        }

        incident.setStatus(IncidentStatus.ROOT_CAUSE_IDENTIFIED);
        incident.setRootCauseAnalysis(request.getRootCauseAnalysis());
        incident.setRootCauseCategory(request.getRootCauseCategory());
        incident.setInvestigationCompletedAt(Instant.now());

        incident = incidentRepository.save(incident);
        log.info("Root cause recorded for incident: number={}, category={}",
                incident.getIncidentNumber(), request.getRootCauseCategory());
        return incident;
    }

    /**
     * Plan corrective action for an incident.
     */
    @Transactional
    public SafetyIncident planCorrectiveAction(UUID incidentId, CorrectiveActionRequest request) {
        SafetyIncident incident = findActiveIncident(incidentId);

        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw new IllegalStateException("Cannot plan corrective action for a closed incident");
        }

        incident.setStatus(IncidentStatus.CORRECTIVE_ACTION_PLANNED);
        incident.setCorrectiveAction(request.getCorrectiveAction());
        incident.setCorrectiveActionOwner(request.getCorrectiveActionOwner());
        incident.setCorrectiveActionDeadline(request.getCorrectiveActionDeadline());
        incident.setPreventiveMeasures(request.getPreventiveMeasures());

        incident = incidentRepository.save(incident);
        log.info("Corrective action planned for incident: number={}, owner={}",
                incident.getIncidentNumber(), request.getCorrectiveActionOwner());
        return incident;
    }

    /**
     * Mark corrective action as completed.
     */
    @Transactional
    public SafetyIncident completeCorrectiveAction(UUID incidentId) {
        SafetyIncident incident = findActiveIncident(incidentId);

        if (incident.getCorrectiveAction() == null) {
            throw new IllegalStateException("No corrective action has been planned for this incident");
        }

        incident.setStatus(IncidentStatus.CORRECTIVE_ACTION_IMPLEMENTED);
        incident.setCorrectiveActionCompletedAt(Instant.now());

        incident = incidentRepository.save(incident);
        log.info("Corrective action completed for incident: number={}", incident.getIncidentNumber());
        return incident;
    }

    /**
     * Close the incident with lessons learned.
     */
    @Transactional
    public SafetyIncident closeIncident(UUID incidentId, CloseIncidentRequest request) {
        SafetyIncident incident = findActiveIncident(incidentId);

        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw new IllegalStateException("Incident is already closed");
        }

        incident.setStatus(IncidentStatus.CLOSED);
        incident.setClosedAt(Instant.now());
        incident.setClosedByName(request.getClosedByName());
        incident.setLessonsLearned(request.getLessonsLearned());

        incident = incidentRepository.save(incident);
        log.info("Safety incident closed: number={}, closedBy={}", incident.getIncidentNumber(), request.getClosedByName());
        return incident;
    }

    /**
     * Get all incidents for a hospital (paginated).
     */
    public Page<SafetyIncident> getIncidentsByHospital(UUID hospitalId, Pageable pageable) {
        return incidentRepository.findByHospitalIdAndIsActiveTrueOrderByIncidentDateTimeDesc(hospitalId, pageable);
    }

    /**
     * Get incidents filtered by type for a hospital (paginated).
     */
    public Page<SafetyIncident> getIncidentsByType(UUID hospitalId, IncidentType type, Pageable pageable) {
        return incidentRepository.findByHospitalIdAndIncidentTypeAndIsActiveTrueOrderByIncidentDateTimeDesc(
                hospitalId, type, pageable);
    }

    /**
     * Get all open (not closed) incidents for a hospital.
     */
    public List<SafetyIncident> getOpenIncidents(UUID hospitalId) {
        return incidentRepository.findOpenIncidents(hospitalId);
    }

    /**
     * Get a single incident by ID.
     */
    public SafetyIncident getIncident(UUID incidentId) {
        return findActiveIncident(incidentId);
    }

    /** The full incident register for a hospital over a date window — for CSV export. */
    public List<SafetyIncident> getIncidentsForExport(UUID hospitalId, Instant from, Instant to) {
        return incidentRepository
                .findByHospitalIdAndIncidentDateTimeBetweenAndIsActiveTrueOrderByIncidentDateTimeDesc(
                        hospitalId, from, to);
    }

    /**
     * Render a single incident's report PDF. Runs in this service's read-only transaction so the
     * lazy hospital association resolves while the PDF is built.
     */
    public SafetyIncidentPdfService.RenderedPdf renderIncidentPdf(UUID incidentId) {
        SafetyIncident incident = findActiveIncident(incidentId);
        return new SafetyIncidentPdfService.RenderedPdf(
                safetyIncidentPdfService.render(incident), safetyIncidentPdfService.filename(incident));
    }

    /**
     * Get incident statistics for a hospital within a date range.
     */
    public IncidentStatsResponse getIncidentStats(UUID hospitalId, Instant from, Instant to) {
        long total = incidentRepository.countByHospitalAndDateRange(hospitalId, from, to);

        Map<String, Long> countByType = new LinkedHashMap<>();
        for (Object[] row : incidentRepository.countByTypeAndDateRange(hospitalId, from, to)) {
            countByType.put(((IncidentType) row[0]).name(), (Long) row[1]);
        }

        Map<String, Long> countBySeverity = new LinkedHashMap<>();
        for (Object[] row : incidentRepository.countBySeverityAndDateRange(hospitalId, from, to)) {
            countBySeverity.put(((IncidentSeverity) row[0]).name(), (Long) row[1]);
        }

        return IncidentStatsResponse.builder()
                .hospitalId(hospitalId)
                .from(from)
                .to(to)
                .totalIncidents(total)
                .countByType(countByType)
                .countBySeverity(countBySeverity)
                .build();
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private SafetyIncident findActiveIncident(UUID incidentId) {
        return incidentRepository.findByIdAndIsActiveTrue(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("SafetyIncident", "id", incidentId));
    }

    private String generateIncidentNumber(Instant timestamp) {
        String datePrefix = "SI-" + DATE_PREFIX_FORMATTER.format(timestamp);
        long count = incidentRepository.countByIncidentNumberPrefix(datePrefix);
        return String.format("%s-%05d", datePrefix, count + 1);
    }

    private void generateCriticalIncidentAlert(SafetyIncident incident, Visit visit) {
        if (visit == null) {
            // Cannot create ClinicalAlert without a visit
            log.warn("CRITICAL safety incident reported without visit context: number={}, severity={}",
                    incident.getIncidentNumber(), incident.getSeverity());
            return;
        }

        String title = String.format("CRITICAL SAFETY INCIDENT: %s — %s",
                incident.getSeverity(), incident.getIncidentType());
        String message = String.format(
                "Patient safety incident reported: %s. Severity: %s. Type: %s. Location: %s. " +
                        "Incident #%s requires immediate administrative review.",
                incident.getDescription() != null && incident.getDescription().length() > 100
                        ? incident.getDescription().substring(0, 100) + "..."
                        : incident.getDescription(),
                incident.getSeverity(),
                incident.getIncidentType(),
                incident.getLocationInHospital() != null ? incident.getLocationInHospital() : "Not specified",
                incident.getIncidentNumber());

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.SAFETY_INCIDENT_CRITICAL)
                .severity(AlertSeverity.CRITICAL)
                .title(title)
                .message(message)
                .autoGenerated(true)
                .escalationTier(1)
                .build();

        clinicalAlertRepository.save(alert);
        log.info("CRITICAL alert generated for safety incident: number={}, severity={}",
                incident.getIncidentNumber(), incident.getSeverity());
    }
}
