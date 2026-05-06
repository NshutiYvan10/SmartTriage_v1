package com.smartTriage.smartTriage_server.module.zonetransfer.service;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.zonetransfer.dto.ZoneTransferResponse;
import com.smartTriage.smartTriage_server.module.zonetransfer.entity.ZoneTransfer;
import com.smartTriage.smartTriage_server.module.zonetransfer.entity.ZoneTransferStatus;
import com.smartTriage.smartTriage_server.module.zonetransfer.mapper.ZoneTransferMapper;
import com.smartTriage.smartTriage_server.module.zonetransfer.repository.ZoneTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ZoneTransferService — owns the state machine for inter-zone moves.
 *
 * <p>State diagram:
 * <pre>
 *   (created) ──► PENDING_ACCEPT ──► ACCEPTED         (zone changes)
 *                       │
 *                       ├────────► DECLINED          (stays in zone)
 *                       ├────────► RESUS_IN_PLACE    (treat in place)
 *                       └────────► CANCELLED         (initiator backed out)
 * </pre>
 *
 * <p>Only PENDING_ACCEPT is a "live" state; the others are terminal
 * and the row is read-only thereafter (except for the soft-delete
 * is_active flag).
 *
 * <p>Visit's current_ed_zone and primary_clinician_id are mutated
 * only at {@link ZoneTransferStatus#ACCEPTED} — that's the safety
 * invariant: zone change ⇔ explicit doctor acceptance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZoneTransferService {

    private final ZoneTransferRepository repository;
    private final VisitRepository visitRepository;
    private final UserRepository userRepository;

    /**
     * Initiate a new zone transfer (or update an existing pending one
     * to a higher target zone). Called from the auto re-triage path
     * and from manual transfer initiation by a clinician.
     *
     * <p>Idempotency: if a PENDING_ACCEPT transfer already exists for
     * this visit and the new target zone is the same severity or
     * lower, return the existing one unchanged. If the new target is
     * a higher severity (e.g. ORANGE pending → bumps to RED), update
     * the existing row's toZone in-place rather than creating a
     * second pending.
     *
     * @param visit the visit being transferred
     * @param fromZone current zone (pulled from the visit unless caller passed something different)
     * @param toZone target zone
     * @param reason templated or free-form reason string
     * @param initiatedBy null for system-initiated transfers
     * @param triggeringAlertId optional FK to clinical_alerts
     * @param triggeringSignEventId optional FK to clinical_sign_events
     * @return the persisted (new or updated) ZoneTransfer
     */
    @Transactional
    public ZoneTransfer initiate(
            Visit visit, EdZone fromZone, EdZone toZone, String reason,
            User initiatedBy, UUID triggeringAlertId, UUID triggeringSignEventId) {

        if (visit == null) throw new IllegalArgumentException("visit is null");
        if (toZone == null) throw new IllegalArgumentException("toZone is null");

        // No-op when the zone wouldn't change.
        if (fromZone == toZone) {
            log.debug("[zone-transfer] No-op: visit {} already in zone {}",
                    visit.getVisitNumber(), toZone);
            // Return whatever pending transfer exists (or null) so the
            // caller can react sensibly without a special-case branch.
            return repository
                    .findFirstByVisitIdAndStatusAndIsActiveTrueOrderByInitiatedAtDesc(
                            visit.getId(), ZoneTransferStatus.PENDING_ACCEPT)
                    .orElse(null);
        }

        Optional<ZoneTransfer> existing = repository
                .findFirstByVisitIdAndStatusAndIsActiveTrueOrderByInitiatedAtDesc(
                        visit.getId(), ZoneTransferStatus.PENDING_ACCEPT);
        if (existing.isPresent()) {
            ZoneTransfer current = existing.get();
            // Severity comparison: the upper-tier zones map onto
            // RESUS > ACUTE > GENERAL/AMBULATORY > others. We use
            // category-equivalent severity inferred from the zone
            // ordering below. Anything not listed (TRIAGE, OBSERVATION,
            // ISOLATION, PEDIATRIC) is treated as "neutral" — pending
            // transfers into those don't get auto-bumped further.
            if (severity(toZone) > severity(current.getToZone())) {
                current.setToZone(toZone);
                if (reason != null) current.setReason(reason);
                if (triggeringAlertId != null) current.setTriggeringAlertId(triggeringAlertId);
                if (triggeringSignEventId != null) current.setTriggeringSignEventId(triggeringSignEventId);
                log.info("[zone-transfer] Updated pending transfer for visit {} → {} (was {})",
                        visit.getVisitNumber(), toZone, current.getFromZone());
                return repository.save(current);
            }
            log.debug("[zone-transfer] Existing pending covers visit {}; not creating duplicate",
                    visit.getVisitNumber());
            return current;
        }

        ZoneTransfer t = ZoneTransfer.builder()
                .visit(visit)
                .fromZone(fromZone)
                .toZone(toZone)
                .status(ZoneTransferStatus.PENDING_ACCEPT)
                .reason(reason)
                .initiatedAt(Instant.now())
                .initiatedBy(initiatedBy)
                .triggeringAlertId(triggeringAlertId)
                .triggeringSignEventId(triggeringSignEventId)
                .build();
        t = repository.save(t);
        log.info("[zone-transfer] Initiated: visit {} {} → {} ({})",
                visit.getVisitNumber(), fromZone, toZone, reason);
        return t;
    }

    /**
     * Accept a pending transfer. Updates the visit's current_ed_zone
     * and primary_clinician_id atomically, marks the transfer as
     * ACCEPTED. The receiving doctor is the authenticated user.
     */
    @Transactional
    public ZoneTransferResponse accept(UUID transferId, String handoverNote) {
        ZoneTransfer t = mustBePending(transferId);
        User accepter = currentUserOrThrow();
        Visit visit = t.getVisit();
        visit.setCurrentEdZone(t.getToZone());
        visit.setPrimaryClinician(accepter);
        visitRepository.save(visit);

        t.setStatus(ZoneTransferStatus.ACCEPTED);
        t.setAcceptedAt(Instant.now());
        t.setAcceptedBy(accepter);
        t.setHandoverNote(handoverNote);
        t = repository.save(t);
        log.info("[zone-transfer] ACCEPTED: visit {} → {} by {}",
                visit.getVisitNumber(), t.getToZone(), composeName(accepter));
        return ZoneTransferMapper.toResponse(t);
    }

    /**
     * Decline a pending transfer (e.g. resus full). Patient stays in
     * original zone; declined_reason explains why.
     */
    @Transactional
    public ZoneTransferResponse decline(UUID transferId, String reason) {
        ZoneTransfer t = mustBePending(transferId);
        User actor = currentUserOrThrow();
        t.setStatus(ZoneTransferStatus.DECLINED);
        t.setDeclinedAt(Instant.now());
        t.setDeclinedBy(actor);
        t.setDeclinedReason(reason);
        t = repository.save(t);
        log.warn("[zone-transfer] DECLINED: visit {} → {} by {} reason={}",
                t.getVisit().getVisitNumber(), t.getToZone(), composeName(actor), reason);
        return ZoneTransferMapper.toResponse(t);
    }

    /**
     * Convert a pending transfer to RESUS_IN_PLACE — the receiving
     * doctor takes co-responsibility but the patient stays in their
     * current physical location. Visit's primary_clinician transfers
     * to the receiving doctor; current_ed_zone STAYS at fromZone
     * (this is the "physically here, clinically there" state).
     */
    @Transactional
    public ZoneTransferResponse markResusInPlace(UUID transferId, String note) {
        ZoneTransfer t = mustBePending(transferId);
        User actor = currentUserOrThrow();
        Visit visit = t.getVisit();
        visit.setPrimaryClinician(actor);
        // current_ed_zone is intentionally NOT changed — that's the
        // "in place" semantics. Alerts continue to fire at the
        // higher-acuity severity from the original auto-bump.
        visitRepository.save(visit);

        t.setStatus(ZoneTransferStatus.RESUS_IN_PLACE);
        t.setAcceptedAt(Instant.now());
        t.setAcceptedBy(actor);
        t.setHandoverNote(note);
        t = repository.save(t);
        log.warn("[zone-transfer] RESUS_IN_PLACE: visit {} stays in {} but treated at {} by {}",
                visit.getVisitNumber(), t.getFromZone(), t.getToZone(), composeName(actor));
        return ZoneTransferMapper.toResponse(t);
    }

    /**
     * Cancel a pending transfer. Used when the system auto-bump is
     * undone within the cooldown window (e.g. clinical sign recorded
     * as ABSENT immediately after a misclick).
     */
    @Transactional
    public ZoneTransferResponse cancel(UUID transferId, String reason) {
        ZoneTransfer t = mustBePending(transferId);
        t.setStatus(ZoneTransferStatus.CANCELLED);
        t.setDeclinedAt(Instant.now());
        t.setDeclinedReason(reason);
        t = repository.save(t);
        log.info("[zone-transfer] CANCELLED: visit {} → {} reason={}",
                t.getVisit().getVisitNumber(), t.getToZone(), reason);
        return ZoneTransferMapper.toResponse(t);
    }

    public List<ZoneTransferResponse> pendingForHospital(UUID hospitalId) {
        return repository.findPendingForHospital(hospitalId).stream()
                .map(ZoneTransferMapper::toResponse).collect(Collectors.toList());
    }

    public List<ZoneTransferResponse> pendingIntoZone(UUID hospitalId, EdZone zone) {
        return repository.findPendingIntoZone(hospitalId, zone).stream()
                .map(ZoneTransferMapper::toResponse).collect(Collectors.toList());
    }

    public List<ZoneTransferResponse> historyForVisit(UUID visitId) {
        return repository.findHistoryForVisit(visitId).stream()
                .map(ZoneTransferMapper::toResponse).collect(Collectors.toList());
    }

    /**
     * Used by the frontend's "is there a pending transfer for this
     * visit?" check on each visit detail load.
     */
    public Optional<ZoneTransferResponse> findPendingForVisit(UUID visitId) {
        return repository
                .findFirstByVisitIdAndStatusAndIsActiveTrueOrderByInitiatedAtDesc(
                        visitId, ZoneTransferStatus.PENDING_ACCEPT)
                .map(ZoneTransferMapper::toResponse);
    }

    // ── helpers ──────────────────────────────────────────────

    private ZoneTransfer mustBePending(UUID transferId) {
        ZoneTransfer t = repository.findByIdAndIsActiveTrue(transferId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ZoneTransfer", "id", transferId));
        if (t.getStatus() != ZoneTransferStatus.PENDING_ACCEPT) {
            throw new ClinicalBusinessException(
                    "Zone transfer is no longer pending (current status: "
                            + t.getStatus() + ")");
        }
        return t;
    }

    /**
     * Severity ordering for zone-update logic. Anything not listed
     * returns 0 (neutral) — TRIAGE/OBSERVATION/ISOLATION/PEDIATRIC
     * don't bump on top of an existing pending.
     */
    private static int severity(EdZone z) {
        if (z == null) return 0;
        return switch (z) {
            case RESUS -> 4;
            case ACUTE -> 3;
            case GENERAL -> 2;
            case AMBULATORY -> 1;
            default -> 0;
        };
    }

    private User currentUserOrThrow() {
        try {
            Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (p instanceof User u) return u;
        } catch (Exception ignored) { /* fall through */ }
        throw new ClinicalBusinessException("No authenticated user for zone-transfer action");
    }

    private static String composeName(User u) {
        if (u == null) return "system";
        String first = u.getFirstName() == null ? "" : u.getFirstName();
        String last = u.getLastName() == null ? "" : u.getLastName();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? u.getUsername() : full;
    }
}
