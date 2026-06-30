package com.smartTriage.smartTriage_server.module.alert.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.EmsRunStatus;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.ems.mapper.EmsRunMapper;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Clinical Alert service — manages system-generated and manually created
 * alerts.
 * Provides the alert queue for the ED dashboard, including zone-aware queries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalAlertService {

    private final ClinicalAlertRepository clinicalAlertRepository;
    private final EmsRunRepository emsRunRepository;
    private final RealTimeEventPublisher eventPublisher;
    private final AlertScopeResolver alertScopeResolver;

    // ── Alert-feed reads ──────────────────────────────────────────────
    // CRITICAL: these MUST map entity → DTO INSIDE this @Transactional(readOnly)
    // service, NOT in the controller. ClinicalAlert.visit / targetDoctor /
    // acknowledgedBy are all @ManyToOne(LAZY); the enriched ClinicalAlertMapper
    // dereferences them. Mapping in the controller (after the tx closed) threw
    // LazyInitializationException → HTTP 500 → the Alert Center showed "feed
    // unavailable" for every hospital that had any escalated/acknowledged alert,
    // even though the live WS dashboard (which never uses this mapper) worked.
    // Mapping here keeps the session open for every lazy field the mapper touches
    // now or in future. Each row is mapped defensively (see toResponses): one
    // un-mappable alert is logged + skipped, never blanking the whole feed.

    public Page<ClinicalAlertResponse> getAlertsForVisit(UUID visitId, Pageable pageable) {
        return toResponses(clinicalAlertRepository.findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(visitId, pageable),
                pageable);
    }

    public Page<ClinicalAlertResponse> getAllAlerts(UUID hospitalId, Pageable pageable) {
        // Role/zone-scoped at the DATA level — a Zone Nurse gets only their zone's
        // alerts (+ ones addressed to them), a Lab Tech only lab alerts, oversight
        // roles everything. This is the authoritative filter, not a UI hide.
        AlertScopeResolver.AlertScope scope = alertScopeResolver.resolve(
                SecurityContextHolder.getContext().getAuthentication(), hospitalId);
        Page<ClinicalAlert> page = switch (scope.kind()) {
            case ALL -> clinicalAlertRepository.findAllAlertsByHospital(hospitalId, pageable);
            case ZONE -> scope.zones().isEmpty()
                    ? clinicalAlertRepository.findPersonalScopedAlerts(hospitalId, scope.userId(), pageable)
                    : clinicalAlertRepository.findZoneScopedAlerts(hospitalId, scope.zones(), scope.userId(), pageable);
            case CATEGORY -> clinicalAlertRepository.findScopedByAlertTypes(hospitalId, scope.alertTypes(), pageable);
            case NONE -> Page.<ClinicalAlert>empty(pageable);
        };
        return toResponses(page, pageable);
    }

    public Page<ClinicalAlertResponse> getUnacknowledgedAlerts(UUID hospitalId, Pageable pageable) {
        // SAME scoping as getAllAlerts — the in-memory alert STORE seeds itself from
        // this endpoint and the dashboard + Alert Center render the store, so an
        // unscoped feed here leaks every alert to every role regardless of zone.
        AlertScopeResolver.AlertScope scope = alertScopeResolver.resolve(
                SecurityContextHolder.getContext().getAuthentication(), hospitalId);
        return toResponses(scopedUnacknowledged(scope, hospitalId, pageable), pageable);
    }

    public Page<ClinicalAlertResponse> getCriticalAlerts(UUID hospitalId, Pageable pageable) {
        AlertScopeResolver.AlertScope scope = alertScopeResolver.resolve(
                SecurityContextHolder.getContext().getAuthentication(), hospitalId);
        if (scope.kind() == AlertScopeResolver.Kind.ALL) {
            // Oversight (e.g. the charge-nurse shift summary) → exact paginated query.
            return toResponses(clinicalAlertRepository.findUnacknowledgedAlertsBySeverity(
                    hospitalId, AlertSeverity.CRITICAL, pageable), pageable);
        }
        // Scoped roles → take the caller's unacknowledged scope, keep only CRITICALs.
        // Criticals are a small subset and the caller requests a large page.
        List<ClinicalAlert> criticals = scopedUnacknowledged(scope, hospitalId, pageable)
                .getContent().stream()
                .filter(a -> a.getSeverity() == AlertSeverity.CRITICAL)
                .toList();
        return new PageImpl<>(toResponses(criticals), pageable, criticals.size());
    }

    /** The caller's UNACKNOWLEDGED alerts, scoped by role/zone (see AlertScopeResolver). */
    private Page<ClinicalAlert> scopedUnacknowledged(AlertScopeResolver.AlertScope scope,
            UUID hospitalId, Pageable pageable) {
        return switch (scope.kind()) {
            case ALL -> clinicalAlertRepository.findUnacknowledgedAlerts(hospitalId, pageable);
            case ZONE -> scope.zones().isEmpty()
                    ? clinicalAlertRepository.findPersonalScopedUnacknowledged(hospitalId, scope.userId(), pageable)
                    : clinicalAlertRepository.findZoneScopedUnacknowledged(
                            hospitalId, scope.zones(), scope.userId(), pageable);
            case CATEGORY -> clinicalAlertRepository.findScopedUnacknowledgedByTypes(
                    hospitalId, scope.alertTypes(), pageable);
            case NONE -> Page.<ClinicalAlert>empty(pageable);
        };
    }

    /**
     * Get unacknowledged alerts for a specific ED zone.
     */
    public List<ClinicalAlertResponse> getUnacknowledgedAlertsByZone(UUID hospitalId, EdZone zone) {
        return toResponses(clinicalAlertRepository.findUnacknowledgedAlertsByZone(hospitalId, zone));
    }

    /**
     * Get unacknowledged alerts targeted at a specific doctor.
     */
    public List<ClinicalAlertResponse> getAlertsForDoctor(UUID doctorId) {
        return toResponses(clinicalAlertRepository.findUnacknowledgedAlertsForDoctor(doctorId));
    }

    // ── Resilient mapping (runs inside this service's transaction) ──
    private Page<ClinicalAlertResponse> toResponses(Page<ClinicalAlert> page, Pageable pageable) {
        return new PageImpl<>(toResponses(page.getContent()), pageable, page.getTotalElements());
    }

    /**
     * Map alerts → DTOs, skipping (and loudly logging) any single row that fails
     * to map. RELIABILITY GUARANTEE: on this life-critical feed, one malformed or
     * un-mappable alert must NEVER take the entire Alert Center down — the
     * clinician still sees every other alert, and the failure is logged (not
     * silently swallowed) for monitoring.
     */
    private List<ClinicalAlertResponse> toResponses(List<ClinicalAlert> alerts) {
        List<ClinicalAlertResponse> out = new java.util.ArrayList<>(alerts.size());
        for (ClinicalAlert a : alerts) {
            try {
                out.add(ClinicalAlertMapper.toResponse(a));
            } catch (Exception e) {
                UUID id = null;
                try { id = a.getId(); } catch (Exception ignored) { /* broken proxy */ }
                log.error("[alert-feed] Skipped un-mappable alert {} — feed stays available. Cause: {}",
                        id, e.toString(), e);
            }
        }
        return out;
    }

    /**
     * Server-side filter for the Phase 14 Override Audit dashboard. The
     * `range` parameter accepts the same shorthand the frontend uses
     * ("24h", "7d", "30d", "all" — case-insensitive). Anything else
     * is treated as "all" rather than throwing, because a malformed
     * query string from a stale link shouldn't take the dashboard down.
     */
    public Page<ClinicalAlertResponse> getSafetyOverrides(
            UUID hospitalId,
            String range,
            Pageable pageable) {
        Instant from = null;
        if (range != null) {
            String normalised = range.trim().toLowerCase();
            Instant now = Instant.now();
            switch (normalised) {
                case "24h" -> from = now.minus(24, ChronoUnit.HOURS);
                case "7d"  -> from = now.minus(7, ChronoUnit.DAYS);
                case "30d" -> from = now.minus(30, ChronoUnit.DAYS);
                default    -> from = null;
            }
        }
        return toResponses(clinicalAlertRepository.findSafetyOverrides(hospitalId, from, null, pageable), pageable);
    }

    @Transactional
    public ClinicalAlertResponse acknowledgeAlert(UUID alertId, String note) {
        ClinicalAlert alert = clinicalAlertRepository.findByIdAndIsActiveTrue(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("ClinicalAlert", "id", alertId));

        alert.setAcknowledged(true);
        alert.setAcknowledgedAt(Instant.now());
        // B5 — persist the acknowledge/dismiss comment (previously dropped).
        // Capped to the column length defensively.
        if (note != null && !note.isBlank()) {
            String trimmed = note.trim();
            alert.setAcknowledgmentNote(trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed);
        }

        // Resolve acknowledging user
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) {
                alert.setAcknowledgedBy(user);
            }
        } catch (Exception e) {
            log.debug("Could not resolve acknowledging user");
        }

        alert = clinicalAlertRepository.save(alert);
        log.info("Alert acknowledged: {} (Type: {} Severity: {} Tier: {})",
                alert.getId(), alert.getAlertType(), alert.getSeverity(), alert.getEscalationTier());

        // Reflect an EMS pre-arrival acknowledgement back to the paramedic:
        // stamp the run so the crew's dashboard shows "ED acknowledged — <name>"
        // (first ack wins). The paramedic otherwise had no "the hospital has
        // seen you" signal until handover.
        if (alert.getAlertType() == AlertType.EMS_PRE_ARRIVAL && alert.getVisit() != null) {
            try {
                String acker = ackerName(alert);
                emsRunRepository.findByVisitIdAndIsActiveTrue(alert.getVisit().getId()).ifPresent(run -> {
                    if (run.getPreArrivalAckedAt() == null
                            && run.getStatus() != EmsRunStatus.HANDED_OFF
                            && run.getStatus() != EmsRunStatus.CANCELLED) {
                        run.setPreArrivalAckedAt(Instant.now());
                        run.setPreArrivalAckedByName(acker);
                        emsRunRepository.save(run);
                        eventPublisher.publishEmsRun(run.getHospital().getId(), EmsRunMapper.toResponse(run));
                    }
                });
            } catch (Exception e) {
                log.warn("Could not stamp EMS pre-arrival ack for visit {}: {}",
                        alert.getVisit().getId(), e.getMessage());
            }
        }

        // Issue-1 sync (the "vice versa" direction): acknowledging the patient-AT-DOOR
        // alert here in the Alert Center RECORDS that the ED received the patient and
        // who/when, so the inbound-ambulance dashboard card reflects it ("Received by
        // <name> — awaiting handover") instead of demanding a second acknowledge. This
        // is the RECEIPT, NOT the formal transfer of care — the read-back handover stays
        // a deliberate step (run goes HANDED_OFF only via transfer-of-care).
        if (alert.getAlertType() == AlertType.EMS_ARRIVED && alert.getVisit() != null) {
            try {
                String acker = ackerName(alert);
                emsRunRepository.findByVisitIdAndIsActiveTrue(alert.getVisit().getId()).ifPresent(run -> {
                    if (run.getArrivalAckedAt() == null
                            && run.getStatus() != EmsRunStatus.HANDED_OFF
                            && run.getStatus() != EmsRunStatus.CANCELLED) {
                        run.setArrivalAckedAt(Instant.now());
                        run.setArrivalAckedByName(acker);
                        emsRunRepository.save(run);
                        eventPublisher.publishEmsRun(run.getHospital().getId(), EmsRunMapper.toResponse(run));
                    }
                });
            } catch (Exception e) {
                log.warn("Could not stamp EMS arrival ack for visit {}: {}",
                        alert.getVisit().getId(), e.getMessage());
            }
        }
        // Map inside the tx (same LazyInit reason as the read paths above).
        return ClinicalAlertMapper.toResponse(alert);
    }

    private static String ackerName(ClinicalAlert alert) {
        User u = alert.getAcknowledgedBy();
        if (u == null) return "ED";
        String n = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                + (u.getLastName() != null ? u.getLastName() : "")).trim();
        return n.isEmpty() ? "ED" : n;
    }
}
