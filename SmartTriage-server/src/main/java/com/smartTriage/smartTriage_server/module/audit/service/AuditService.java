package com.smartTriage.smartTriage_server.module.audit.service;

import com.smartTriage.smartTriage_server.module.audit.context.AuditContext;
import com.smartTriage.smartTriage_server.module.audit.dto.AuditLogResponse;
import com.smartTriage.smartTriage_server.module.audit.entity.AuditLog;
import com.smartTriage.smartTriage_server.module.audit.repository.AuditLogRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes and reads the persisted audit log. {@link #record} runs in its OWN
 * transaction and is fail-safe — auditing must never break or roll back the
 * business request it is recording.
 *
 * <p>V107: every recorded action is additionally attributed to the VISIT and
 * PATIENT it touched, resolved from {@link AuditContext} (fed by ClinicalAuthz,
 * which already resolves which visit each clinical endpoint targets) with a
 * path-pattern fallback. This is what lets the hospital admin reconstruct
 * "who did what to THIS patient" for incident reporting — including failed and
 * DENIED attempts, which carry the same attribution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final VisitRepository visitRepository;
    private final AuditContext auditContext;

    /** Fallback visit extraction for paths shaped .../visit/{uuid}... (belt-and-braces). */
    private static final Pattern VISIT_IN_PATH = Pattern.compile(
            "/visits?/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String httpMethod, String path, String action, int statusCode) {
        try {
            // Prefer the context actor (set by AuthService on login/refresh, where the
            // SecurityContext principal is not yet a User and rows used to say "anonymous").
            User user = auditContext.getActor();
            if (user == null) user = currentUser();
            UUID actorId = user != null ? user.getId() : null;
            UUID hospitalId = actorId != null
                    ? userRepository.findHospitalIdByUserId(actorId).orElse(null) : null;

            // Patient linkage: the visit resolved during authorization, else from the path.
            UUID visitId = auditContext.getVisitId();
            if (visitId == null) visitId = extractVisitIdFromPath(path);
            UUID patientId = visitId != null
                    ? visitRepository.findPatientIdByVisitId(visitId).orElse(null) : null;

            AuditLog entry = AuditLog.builder()
                    .actorUserId(actorId)
                    .actorName(user != null ? displayName(user) : "anonymous")
                    .actorRole(user != null && user.getRole() != null ? user.getRole().name() : null)
                    .hospitalId(hospitalId)
                    .visitId(visitId)
                    .patientId(patientId)
                    .httpMethod(httpMethod)
                    .path(path != null && path.length() > 512 ? path.substring(0, 512) : path)
                    .action(action)
                    .statusCode(statusCode)
                    .outcome(statusCode < 400 ? "SUCCESS" : "FAILED")
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // Never propagate — a failed audit write must not fail the request.
            log.warn("Failed to write audit log for {} {}: {}", httpMethod, path, e.getMessage());
        }
    }

    /**
     * Filtered, paged hospital audit search (V107): optional time range, actor,
     * outcome (SUCCESS/FAILED), and a free-text needle matched against the action
     * label, the raw path and the actor name. Always newest-first.
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> search(UUID hospitalId, Instant from, Instant to,
                                 UUID actorUserId, String outcome, String q, Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> cb.equal(root.get("hospitalId"), hospitalId);
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }
        if (actorUserId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("actorUserId"), actorUserId));
        }
        if (outcome != null && !outcome.isBlank()) {
            String o = outcome.trim().toUpperCase();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("outcome"), o));
        }
        if (q != null && !q.isBlank()) {
            String needle = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("action")), needle),
                    cb.like(cb.lower(root.get("path")), needle),
                    cb.like(cb.lower(root.get("actorName")), needle)));
        }
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return auditLogRepository.findAll(spec, sorted);
    }

    /** Back-compat unfiltered read (kept for callers that only page by time). */
    @Transactional(readOnly = true)
    public Page<AuditLog> getForHospital(UUID hospitalId, Instant from, Instant to, Pageable pageable) {
        if (from != null && to != null) {
            return auditLogRepository
                    .findByHospitalIdAndCreatedAtBetweenOrderByCreatedAtDesc(hospitalId, from, to, pageable);
        }
        return auditLogRepository.findByHospitalIdOrderByCreatedAtDesc(hospitalId, pageable);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getForHospitalRange(UUID hospitalId, Instant from, Instant to) {
        return auditLogRepository
                .findByHospitalIdAndCreatedAtBetweenOrderByCreatedAtDesc(hospitalId, from, to);
    }

    /**
     * The incident timeline (V107): every audited action that touched this visit,
     * oldest first — who did what, when, with what outcome, including failed and
     * denied attempts.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> getForVisit(UUID visitId) {
        return auditLogRepository.findByVisitIdOrderByCreatedAtAsc(visitId);
    }

    /**
     * Batch-resolve visit display refs (visitNumber + patient name) onto mapped
     * responses — ONE query per page, so audit rows read as "Kanamugire Hope
     * (V-2026-0042)" instead of a bare UUID.
     */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> enrichWithVisitRefs(List<AuditLogResponse> rows) {
        List<UUID> visitIds = rows.stream()
                .map(AuditLogResponse::getVisitId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (visitIds.isEmpty()) return rows;
        Map<UUID, String[]> refs = loadVisitRefs(visitIds);
        for (AuditLogResponse r : rows) {
            String[] ref = r.getVisitId() != null ? refs.get(r.getVisitId()) : null;
            if (ref != null) {
                r.setVisitNumber(ref[0]);
                r.setPatientName(ref[1]);
            }
        }
        return rows;
    }

    /** visitId → [visitNumber, patientName]. */
    private Map<UUID, String[]> loadVisitRefs(Collection<UUID> visitIds) {
        Map<UUID, String[]> refs = new HashMap<>();
        for (Object[] row : visitRepository.findAuditDisplayRefsByVisitIds(visitIds)) {
            UUID id = (UUID) row[0];
            String visitNumber = (String) row[1];
            String name = ((row[2] != null ? row[2] : "") + " " + (row[3] != null ? row[3] : "")).trim();
            refs.put(id, new String[]{visitNumber, name.isEmpty() ? null : name});
        }
        return refs;
    }

    private static UUID extractVisitIdFromPath(String path) {
        if (path == null) return null;
        Matcher m = VISIT_IN_PATH.matcher(path);
        return m.find() ? UUID.fromString(m.group(1)) : null;
    }

    private User currentUser() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            return principal instanceof User ? (User) principal : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayName(User user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        if ((first == null || first.isBlank()) && (last == null || last.isBlank())) {
            return user.getEmail();
        }
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) sb.append(first.trim());
        if (last != null && !last.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(last.trim());
        }
        return sb.toString();
    }
}
