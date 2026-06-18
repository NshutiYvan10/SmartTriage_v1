package com.smartTriage.smartTriage_server.module.audit.service;

import com.smartTriage.smartTriage_server.module.audit.entity.AuditLog;
import com.smartTriage.smartTriage_server.module.audit.repository.AuditLogRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes and reads the persisted audit log. {@link #record} runs in its OWN
 * transaction and is fail-safe — auditing must never break or roll back the
 * business request it is recording.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String httpMethod, String path, String action, int statusCode) {
        try {
            User user = currentUser();
            UUID actorId = user != null ? user.getId() : null;
            UUID hospitalId = actorId != null
                    ? userRepository.findHospitalIdByUserId(actorId).orElse(null) : null;
            AuditLog entry = AuditLog.builder()
                    .actorUserId(actorId)
                    .actorName(user != null ? displayName(user) : "anonymous")
                    .actorRole(user != null && user.getRole() != null ? user.getRole().name() : null)
                    .hospitalId(hospitalId)
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
