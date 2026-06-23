package com.smartTriage.smartTriage_server.module.consent.service;

import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.consent.dto.BreakTheGlassEventResponse;
import com.smartTriage.smartTriage_server.module.consent.entity.BreakTheGlassEvent;
import com.smartTriage.smartTriage_server.module.consent.mapper.BreakTheGlassEventMapper;
import com.smartTriage.smartTriage_server.module.consent.repository.BreakTheGlassEventRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Governance read + sign-off over break-the-glass emergency overrides (Phase 3). The feed is scoped
 * to the actor's hospital (the team with authority to review its own clinicians). Acknowledgement is
 * a review overlay — the forensic facts (actor, reason, prior consent state, accessed-at) are NEVER
 * mutated; only the reviewer's sign-off is recorded, from the authenticated principal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreakTheGlassEventService {

    private final BreakTheGlassEventRepository repository;

    /**
     * Paginated governance feed for a hospital. {@code range} is "24h" | "7d" | "30d" | "all"
     * (case-insensitive); anything else is treated as all-time. Mirrors
     * {@code ClinicalAlertService.getSafetyOverrides}.
     */
    public Page<BreakTheGlassEventResponse> getEventsForHospital(UUID hospitalId, String range, Pageable pageable) {
        Instant from = null;
        if (range != null) {
            Instant now = Instant.now();
            switch (range.trim().toLowerCase()) {
                case "24h" -> from = now.minus(24, ChronoUnit.HOURS);
                case "7d"  -> from = now.minus(7, ChronoUnit.DAYS);
                case "30d" -> from = now.minus(30, ChronoUnit.DAYS);
                default    -> from = null;
            }
        }
        Page<BreakTheGlassEvent> page = (from == null)
                ? repository.findByActorHospitalIdAndIsActiveTrueOrderByAccessedAtDesc(hospitalId, pageable)
                : repository.findByActorHospitalIdAndAccessedAtGreaterThanEqualAndIsActiveTrueOrderByAccessedAtDesc(
                        hospitalId, from, pageable);
        return page.map(BreakTheGlassEventMapper::toResponse);
    }

    /**
     * Governance sign-off. The event must belong to {@code hospitalId} (the caller is authorized
     * for that hospital's overrides) — a cross-hospital acknowledge is denied. Forensic facts are
     * untouched.
     */
    @Transactional
    public BreakTheGlassEventResponse acknowledgeEvent(UUID eventId, UUID hospitalId, String note) {
        BreakTheGlassEvent event = repository.findByIdAndIsActiveTrue(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("BreakTheGlassEvent", "id", eventId));
        if (event.getActorHospitalId() == null || !event.getActorHospitalId().equals(hospitalId)) {
            throw new AccessDeniedException(
                    "This break-the-glass event does not belong to the specified hospital.");
        }
        User reviewer = resolveCurrentUserOrThrow();
        event.setAcknowledged(true);
        event.setAcknowledgedByUserId(reviewer.getId());
        event.setAcknowledgedByName(displayNameOf(reviewer));
        event.setAcknowledgedAt(Instant.now());
        event.setAcknowledgmentNote(note);
        repository.save(event);
        log.info("Break-the-glass event {} acknowledged by {} ({})",
                event.getId(), reviewer.getId(), hospitalId);
        return BreakTheGlassEventMapper.toResponse(event);
    }

    // ── authenticated-reviewer resolution (mirrors the consent services) ──
    private User resolveCurrentUserOrThrow() {
        Object principal = null;
        try {
            principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        } catch (Exception e) {
            log.warn("No authentication present when acknowledging a break-the-glass event");
        }
        if (principal instanceof User user) {
            return user;
        }
        throw new AccessDeniedException(
                "A break-the-glass event must be acknowledged by an authenticated reviewer.");
    }

    private static String displayNameOf(User user) {
        String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String last = user.getLastName() != null ? user.getLastName().trim() : "";
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? user.getEmail() : joined;
    }
}
