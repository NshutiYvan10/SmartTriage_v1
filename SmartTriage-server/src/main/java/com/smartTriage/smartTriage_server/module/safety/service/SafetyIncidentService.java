package com.smartTriage.smartTriage_server.module.safety.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.safety.dto.*;
import com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident;
import com.smartTriage.smartTriage_server.module.safety.repository.SafetyIncidentRepository;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final ShiftAssignmentService shiftAssignmentService;

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

        // Reporter identity is SERVER-STAMPED from the authenticated principal — a
        // client-supplied name is never trusted (the V110 actor-identity doctrine).
        // Anonymous reports store NO identity at all: anonymity that relies on the
        // read-side mapper hiding a stored name is not anonymity. The request's
        // reportedByName is only a last-resort fallback for principal-less contexts.
        String reporterName;
        String reporterRole;
        if (request.isAnonymous()) {
            reporterName = "Anonymous";
            reporterRole = null;
        } else {
            User principal = resolveCurrentUser();
            reporterName = principal != null
                    ? (principal.getFirstName() + " " + principal.getLastName()).trim()
                    : (request.getReportedByName() != null ? request.getReportedByName() : "Unknown");
            reporterRole = principal != null && principal.getRole() != null
                    ? principal.getRole().name()
                    : request.getReportedByRole();
        }

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
                .reportedByName(reporterName)
                .reportedByRole(reporterRole)
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

        return hydrateForResponse(incident);
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

        IncidentSeverity previousSeverity = incident.getSeverity();

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

        // If severity was UPGRADED into the severe band, alert — but only on the
        // transition (a repeated update at the same severity must not re-page).
        boolean nowSevere = request.getSeverity() == IncidentSeverity.SEVERE_HARM
                || request.getSeverity() == IncidentSeverity.DEATH;
        boolean wasSevere = previousSeverity == IncidentSeverity.SEVERE_HARM
                || previousSeverity == IncidentSeverity.DEATH;
        if (request.getSeverity() != null && nowSevere && !wasSevere) {
            generateCriticalIncidentAlert(incident, incident.getVisit());
        }

        incident = incidentRepository.save(incident);
        log.info("Safety incident updated: id={}, number={}", incidentId, incident.getIncidentNumber());
        return hydrateForResponse(incident);
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
        return hydrateForResponse(incident);
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
        return hydrateForResponse(incident);
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
        return hydrateForResponse(incident);
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
        return hydrateForResponse(incident);
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

        // GOVERNANCE GATE: the worst-severity incidents cannot be closed without the
        // work the register exists to enforce — a completed root-cause analysis AND a
        // corrective action. Milder incidents (near-miss / no-harm / mild / moderate)
        // may close directly, keeping low-stakes reporting lightweight.
        boolean severe = incident.getSeverity() == IncidentSeverity.SEVERE_HARM
                || incident.getSeverity() == IncidentSeverity.DEATH;
        if (severe) {
            if (incident.getRootCauseAnalysis() == null || incident.getRootCauseAnalysis().isBlank()) {
                throw new IllegalStateException(
                        "A " + incident.getSeverity() + " incident cannot be closed without a completed "
                        + "root-cause analysis. Record the investigation findings first.");
            }
            if (incident.getCorrectiveAction() == null || incident.getCorrectiveAction().isBlank()) {
                throw new IllegalStateException(
                        "A " + incident.getSeverity() + " incident cannot be closed without a corrective "
                        + "action. Plan the corrective action first.");
            }
        }

        incident.setStatus(IncidentStatus.CLOSED);
        incident.setClosedAt(Instant.now());
        // Closer identity is server-stamped (actor-identity doctrine); the request
        // value is only a fallback for principal-less contexts.
        User closer = resolveCurrentUser();
        incident.setClosedByName(closer != null
                ? (closer.getFirstName() + " " + closer.getLastName()).trim()
                : request.getClosedByName());
        incident.setLessonsLearned(request.getLessonsLearned());

        incident = incidentRepository.save(incident);
        log.info("Safety incident closed: number={}, closedBy={}", incident.getIncidentNumber(), request.getClosedByName());
        return hydrateForResponse(incident);
    }

    /**
     * Get all incidents for a hospital (paginated).
     */
    public Page<SafetyIncident> getIncidentsByHospital(UUID hospitalId, Pageable pageable) {
        Page<SafetyIncident> page = incidentRepository
                .findByHospitalIdAndIsActiveTrueOrderByIncidentDateTimeDesc(hospitalId, pageable);
        page.forEach(this::hydrateForResponse);
        return page;
    }

    /**
     * Get incidents filtered by type for a hospital (paginated).
     */
    public Page<SafetyIncident> getIncidentsByType(UUID hospitalId, IncidentType type, Pageable pageable) {
        Page<SafetyIncident> page = incidentRepository
                .findByHospitalIdAndIncidentTypeAndIsActiveTrueOrderByIncidentDateTimeDesc(
                        hospitalId, type, pageable);
        page.forEach(this::hydrateForResponse);
        return page;
    }

    /**
     * Get all open (not closed) incidents for a hospital.
     */
    public List<SafetyIncident> getOpenIncidents(UUID hospitalId) {
        List<SafetyIncident> rows = incidentRepository.findOpenIncidents(hospitalId);
        rows.forEach(this::hydrateForResponse);
        return rows;
    }

    /**
     * Get a single incident by ID.
     */
    public SafetyIncident getIncident(UUID incidentId) {
        return hydrateForResponse(findActiveIncident(incidentId));
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
    public SafetyIncidentPdfService.RenderedPdf renderIncidentPdf(UUID incidentId, String exportedBy) {
        SafetyIncident incident = findActiveIncident(incidentId);
        return new SafetyIncidentPdfService.RenderedPdf(
                safetyIncidentPdfService.render(incident, exportedBy),
                safetyIncidentPdfService.filename(incident));
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

    /**
     * Initialize the lazy associations {@link com.smartTriage.smartTriage_server.module.safety.mapper.SafetyIncidentMapper}
     * reads (hospital name, visit number) so the controller can map AFTER this service's
     * transaction closes without a LazyInitializationException — the recurring
     * lazy-mapping 500. It never fired here only because the register was empty until
     * now: the FIRST listed row would have 500'd the whole page. Null-safe.
     */
    private SafetyIncident hydrateForResponse(SafetyIncident incident) {
        if (incident != null) {
            org.hibernate.Hibernate.initialize(incident.getHospital());
            org.hibernate.Hibernate.initialize(incident.getVisit());
        }
        return incident;
    }

    private String generateIncidentNumber(Instant timestamp) {
        String datePrefix = "SI-" + DATE_PREFIX_FORMATTER.format(timestamp);
        long count = incidentRepository.countByIncidentNumberPrefix(datePrefix);
        return String.format("%s-%05d", datePrefix, count + 1);
    }

    /**
     * Page governance about a SEVERE_HARM / DEATH incident. Two delivery shapes:
     * <ul>
     *   <li><b>Visit-linked</b>: a persisted, OWNED {@link ClinicalAlert} (target zone +
     *       zone doctor), pushed after commit to the hospital board, the zone, the zone
     *       doctor and the charge nurses — the standard owned-alert pattern. Deduped on
     *       an existing unacknowledged SAFETY_INCIDENT_CRITICAL for the same visit.</li>
     *   <li><b>Visit-less</b> (equipment failure in a corridor, etc.): {@code ClinicalAlert}
     *       requires a visit (every Alert-Center query joins through it), so the durable
     *       record is the incident REGISTER row itself; delivery is a real-time push of a
     *       transient alert payload to the hospital board + charge nurses. Previously this
     *       case produced nothing but a server log line — a DEATH report nobody saw.</li>
     * </ul>
     */
    private void generateCriticalIncidentAlert(SafetyIncident incident, Visit visit) {
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

        UUID hospitalId = incident.getHospital() != null ? incident.getHospital().getId() : null;

        if (visit == null) {
            publishTransientIncidentAlert(incident, hospitalId, title, message);
            return;
        }

        // Dedup: an unacknowledged critical-incident alert already on this visit means
        // governance has been paged and nobody has acted yet — don't stack duplicates.
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.SAFETY_INCIDENT_CRITICAL)) {
            log.info("SAFETY_INCIDENT_CRITICAL already unacknowledged for visit {} — not re-paging (incident {})",
                    visit.getId(), incident.getIncidentNumber());
            return;
        }

        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = resolveZoneDoctor(hospitalId, zone);
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.SAFETY_INCIDENT_CRITICAL)
                .severity(AlertSeverity.CRITICAL)
                .title(title)
                .message(message)
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .escalationTier(1)
                .build();
        alert = clinicalAlertRepository.save(alert);
        publishOwnedAlert(alert, hospitalId, zone, zoneDoctor);
        log.warn("CRITICAL alert generated for safety incident: number={}, severity={}, zone={}",
                incident.getIncidentNumber(), incident.getSeverity(), zone);
    }

    /**
     * Visit-less severe incident: push a TRANSIENT alert payload (id = incident id, so
     * client-side dedup holds) to the hospital board + charge nurses after commit. The
     * durable record is the register row; the follow-up monitor keeps re-paging while
     * the incident sits unattended in REPORTED.
     */
    private void publishTransientIncidentAlert(SafetyIncident incident, UUID hospitalId,
                                               String title, String message) {
        if (hospitalId == null) return;
        var resp = com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse.builder()
                .id(incident.getId())
                .alertType(AlertType.SAFETY_INCIDENT_CRITICAL)
                .category(AlertType.SAFETY_INCIDENT_CRITICAL.getCategory())
                .severity(AlertSeverity.CRITICAL)
                .title(title)
                .message(message + " (No patient visit linked — review it in the Safety Incidents register.)")
                .acknowledged(false)
                .autoGenerated(true)
                .createdAt(Instant.now())
                .build();
        final List<UUID> chargeNurseIds = shiftAssignmentService.getChargeNurse(hospitalId)
                .stream().map(User::getId).toList();
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                for (UUID cnId : chargeNurseIds) {
                    realTimeEventPublisher.publishUserAlert(cnId, resp);
                }
            } catch (Exception e) {
                log.warn("Failed to publish visit-less safety-incident alert {}: {}",
                        incident.getIncidentNumber(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { fire.run(); }
            });
        } else {
            fire.run();
        }
        log.warn("Visit-less CRITICAL safety incident {} — transient page pushed to hospital board + {} charge nurse(s)",
                incident.getIncidentNumber(), chargeNurseIds.size());
    }

    /** Push a persisted alert to the hospital board + zone + zone doctor + charge nurses AFTER COMMIT. */
    private void publishOwnedAlert(ClinicalAlert alert, UUID hospitalId, EdZone zone, User zoneDoctor) {
        if (hospitalId == null || alert == null) return;
        final var resp = ClinicalAlertMapper.toResponse(alert);
        final UUID doctorId = zoneDoctor != null ? zoneDoctor.getId() : null;
        final List<UUID> chargeNurseIds = shiftAssignmentService.getChargeNurse(hospitalId)
                .stream().map(User::getId).toList();
        final UUID alertId = alert.getId();
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                if (zone != null) realTimeEventPublisher.publishZoneAlert(hospitalId, zone, resp);
                if (doctorId != null) realTimeEventPublisher.publishUserAlert(doctorId, resp);
                for (UUID cnId : chargeNurseIds) {
                    realTimeEventPublisher.publishUserAlert(cnId, resp);
                }
            } catch (Exception e) {
                log.warn("Failed to publish safety-incident alert {}: {}", alertId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { fire.run(); }
            });
        } else {
            fire.run();
        }
    }

    private User resolveZoneDoctor(UUID hospitalId, EdZone zone) {
        if (hospitalId == null || zone == null) return null;
        List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
        return doctors.isEmpty() ? null : doctors.get(0);
    }

    private User resolveCurrentUser() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) return user;
        } catch (Exception ignored) {
            // no resolvable principal (scheduled / system context)
        }
        return null;
    }
}
