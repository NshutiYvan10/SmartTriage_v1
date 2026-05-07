package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.SwapStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftSwapDtos;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftSwapRequest;
import com.smartTriage.smartTriage_server.module.shift.mapper.ShiftSwapMapper;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftAssignmentRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftSwapRequestRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Workflow engine for {@link ShiftSwapRequest}.
 *
 * <p>State transitions (see {@link SwapStatus} for the full diagram):
 *
 * <ul>
 *   <li>{@link #propose} — REQUESTED row, immediately advanced to
 *       PENDING_PARTNER_ACCEPT (we don't have a separate "draft" stage —
 *       creating the request notifies the partner).</li>
 *   <li>{@link #partnerAccept} — partner OKs; status moves to
 *       PENDING_CHARGE_APPROVAL.</li>
 *   <li>{@link #partnerReject} — partner declines; terminal REJECTED.</li>
 *   <li>{@link #cancel} — proposer or partner withdraws; terminal CANCELLED.</li>
 *   <li>{@link #chargeApprove} — CN approves AND <em>atomically applies</em>
 *       the swap: the two ShiftAssignment rows have their {@code user} (and
 *       any user-specific fields) exchanged. This is the only place the
 *       roster mutates.</li>
 *   <li>{@link #chargeReject} — CN declines; terminal REJECTED.</li>
 * </ul>
 *
 * <p>Atomicity: the chargeApprove method runs inside a single
 * {@code @Transactional} unit. If anything fails between the swap and the
 * status flip, the whole thing rolls back — the partial unique indices
 * on {@code shift_swap_requests} guarantee no second swap can squeeze in
 * during that window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftSwapService {

    private final ShiftSwapRequestRepository swapRepository;
    private final ShiftAssignmentRepository assignmentRepository;

    @Transactional
    public ShiftSwapDtos.Response propose(User actor, ShiftSwapDtos.CreateRequest request) {
        ShiftAssignment requesterAssignment = loadActiveAssignment(request.getRequesterAssignmentId());
        ShiftAssignment partnerAssignment   = loadActiveAssignment(request.getPartnerAssignmentId());

        if (requesterAssignment.getId().equals(partnerAssignment.getId())) {
            throw new ClinicalBusinessException("Cannot swap an assignment with itself");
        }
        if (!requesterAssignment.getHospital().getId().equals(partnerAssignment.getHospital().getId())) {
            throw new ClinicalBusinessException(
                    "Cross-hospital swaps are not supported");
        }
        if (!requesterAssignment.getUser().getId().equals(actor.getId())) {
            throw new ClinicalBusinessException(
                    "You can only propose a swap from your own assignment");
        }
        if (requesterAssignment.getUser().getId().equals(partnerAssignment.getUser().getId())) {
            throw new ClinicalBusinessException(
                    "Both sides of the swap belong to the same user");
        }

        // Don't propose against a row that's already in an open swap.
        swapRepository.findOpenForAssignment(requesterAssignment.getId())
                .ifPresent(s -> { throw new ClinicalBusinessException(
                        "Your assignment is already in an open swap request"); });
        swapRepository.findOpenForAssignment(partnerAssignment.getId())
                .ifPresent(s -> { throw new ClinicalBusinessException(
                        "The partner's assignment is already in an open swap request"); });

        ShiftSwapRequest row = ShiftSwapRequest.builder()
                .hospital(requesterAssignment.getHospital())
                .requesterAssignment(requesterAssignment)
                .partnerAssignment(partnerAssignment)
                .requesterUser(requesterAssignment.getUser())
                .partnerUser(partnerAssignment.getUser())
                .status(SwapStatus.PENDING_PARTNER_ACCEPT)
                .requestReason(request.getRequestReason())
                .build();

        row = swapRepository.save(row);
        log.info("Swap {} proposed: {} ↔ {}",
                row.getId(),
                requesterAssignment.getUser().getEmail(),
                partnerAssignment.getUser().getEmail());
        return ShiftSwapMapper.toResponse(row);
    }

    @Transactional
    public ShiftSwapDtos.Response partnerAccept(UUID swapId, User actor, ShiftSwapDtos.DecisionRequest req) {
        ShiftSwapRequest row = loadOpen(swapId);
        if (!row.getPartnerUser().getId().equals(actor.getId())) {
            throw new ClinicalBusinessException("Only the named partner can accept this swap");
        }
        if (row.getStatus() != SwapStatus.PENDING_PARTNER_ACCEPT) {
            throw new ClinicalBusinessException(
                    "Swap is not awaiting partner acceptance (current: " + row.getStatus() + ")");
        }
        row.setStatus(SwapStatus.PENDING_CHARGE_APPROVAL);
        row.setPartnerRespondedAt(Instant.now());
        row.setPartnerResponseNote(req != null ? req.getNote() : null);
        row = swapRepository.save(row);
        log.info("Swap {} accepted by partner {}", swapId, actor.getEmail());
        return ShiftSwapMapper.toResponse(row);
    }

    @Transactional
    public ShiftSwapDtos.Response partnerReject(UUID swapId, User actor, ShiftSwapDtos.DecisionRequest req) {
        ShiftSwapRequest row = loadOpen(swapId);
        if (!row.getPartnerUser().getId().equals(actor.getId())) {
            throw new ClinicalBusinessException("Only the named partner can reject this swap");
        }
        if (row.getStatus() != SwapStatus.PENDING_PARTNER_ACCEPT) {
            throw new ClinicalBusinessException(
                    "Swap is not awaiting partner response (current: " + row.getStatus() + ")");
        }
        row.setStatus(SwapStatus.REJECTED);
        row.setPartnerRespondedAt(Instant.now());
        row.setPartnerResponseNote(req != null ? req.getNote() : null);
        row.setRejectionReason(req != null ? req.getNote() : null);
        row = swapRepository.save(row);
        log.info("Swap {} rejected by partner {}", swapId, actor.getEmail());
        return ShiftSwapMapper.toResponse(row);
    }

    @Transactional
    public ShiftSwapDtos.Response cancel(UUID swapId, User actor) {
        ShiftSwapRequest row = loadOpen(swapId);
        boolean isParticipant =
                row.getRequesterUser().getId().equals(actor.getId())
                || row.getPartnerUser().getId().equals(actor.getId());
        if (!isParticipant) {
            throw new ClinicalBusinessException(
                    "Only the requester or named partner may cancel this swap");
        }
        row.setStatus(SwapStatus.CANCELLED);
        row.setCancelledAt(Instant.now());
        row.setCancelledBy(actor);
        row = swapRepository.save(row);
        log.info("Swap {} cancelled by {}", swapId, actor.getEmail());
        return ShiftSwapMapper.toResponse(row);
    }

    /**
     * The Charge Nurse approves the swap.
     *
     * <p>This is where the roster actually mutates — we exchange the
     * {@code user} on the two underlying ShiftAssignment rows so that the
     * partner now holds the requester's slot and vice-versa. The two rows
     * keep their original {@code shiftDate / shiftPeriod / zone /
     * shiftFunction} so alert routing and the shift board pick up the new
     * arrangement on the next read.
     *
     * <p>We exchange the <em>user</em>, not the slot, because:
     * <ul>
     *   <li>The slot encodes "this zone needs this function on this shift" —
     *       that's what the CN designed; only who fills it changes.</li>
     *   <li>Anything keyed on the assignment id (alert subscriptions,
     *       audit links) keeps pointing at the same row.</li>
     * </ul>
     *
     * <p>If a clinical-competence re-check is needed (e.g. RESUS minimum
     * ALS-credentialed staffing), the CN performs it visually before
     * clicking approve. A future hook can run a programmatic check here
     * and either auto-reject or warn.
     */
    @Transactional
    public ShiftSwapDtos.Response chargeApprove(
            UUID swapId, User actor, ShiftSwapDtos.DecisionRequest req) {
        ShiftSwapRequest row = loadOpen(swapId);
        if (row.getStatus() != SwapStatus.PENDING_CHARGE_APPROVAL) {
            throw new ClinicalBusinessException(
                    "Swap is not awaiting Charge Nurse approval (current: " + row.getStatus() + ")");
        }

        ShiftAssignment a = row.getRequesterAssignment();
        ShiftAssignment b = row.getPartnerAssignment();

        // Sanity: both rows must still be active. If a CN deactivated one
        // of them while the swap was open, the partial unique indices
        // wouldn't have caught it (they only fire on insert). Reject.
        if (!a.isActive() || !b.isActive()) {
            row.setStatus(SwapStatus.REJECTED);
            row.setRejectionReason("One of the assignments is no longer active");
            row.setChargeRespondedAt(Instant.now());
            row.setChargeResponder(actor);
            row.setChargeResponseNote(req != null ? req.getNote() : null);
            ShiftSwapRequest saved = swapRepository.save(row);
            log.warn("Swap {} auto-rejected: assignment(s) deactivated", swapId);
            return ShiftSwapMapper.toResponse(saved);
        }

        // Atomic user exchange.
        User userA = a.getUser();
        User userB = b.getUser();

        // Don't transfer the shift-lead badge across — that authority is
        // bound to the original named CN. If the partner ends up on the
        // CN slot they're acting as ZONE_NURSE (or whatever the slot
        // function is); the badge remains where it was.
        a.setUser(userB);
        b.setUser(userA);

        assignmentRepository.save(a);
        assignmentRepository.save(b);

        row.setStatus(SwapStatus.APPROVED);
        row.setChargeRespondedAt(Instant.now());
        row.setChargeResponder(actor);
        row.setChargeResponseNote(req != null ? req.getNote() : null);
        ShiftSwapRequest saved = swapRepository.save(row);

        log.info("Swap {} APPROVED by CN {} — {} ↔ {} on assignments {} / {}",
                swapId, actor.getEmail(),
                userA.getEmail(), userB.getEmail(), a.getId(), b.getId());
        return ShiftSwapMapper.toResponse(saved);
    }

    @Transactional
    public ShiftSwapDtos.Response chargeReject(
            UUID swapId, User actor, ShiftSwapDtos.DecisionRequest req) {
        ShiftSwapRequest row = loadOpen(swapId);
        if (row.getStatus() != SwapStatus.PENDING_CHARGE_APPROVAL) {
            throw new ClinicalBusinessException(
                    "Swap is not awaiting Charge Nurse approval (current: " + row.getStatus() + ")");
        }
        if (req == null || req.getNote() == null || req.getNote().isBlank()) {
            throw new ClinicalBusinessException("A rejection reason is required");
        }
        row.setStatus(SwapStatus.REJECTED);
        row.setChargeRespondedAt(Instant.now());
        row.setChargeResponder(actor);
        row.setChargeResponseNote(req.getNote());
        row.setRejectionReason(req.getNote());
        row = swapRepository.save(row);
        log.info("Swap {} rejected by CN {}", swapId, actor.getEmail());
        return ShiftSwapMapper.toResponse(row);
    }

    public List<ShiftSwapDtos.Response> listOpenForUser(UUID userId) {
        return swapRepository.findOpenForUser(userId).stream()
                .map(ShiftSwapMapper::toResponse).toList();
    }

    public List<ShiftSwapDtos.Response> listHistoryForUser(UUID userId) {
        return swapRepository.findHistoryForUser(userId).stream()
                .map(ShiftSwapMapper::toResponse).toList();
    }

    public List<ShiftSwapDtos.Response> listChargeQueue(UUID hospitalId) {
        return swapRepository.findPendingChargeApprovalAtHospital(hospitalId).stream()
                .map(ShiftSwapMapper::toResponse).toList();
    }

    /* ─── helpers ─── */

    private ShiftAssignment loadActiveAssignment(UUID id) {
        ShiftAssignment a = assignmentRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftAssignment", "id", id));
        return a;
    }

    private ShiftSwapRequest loadOpen(UUID id) {
        ShiftSwapRequest s = swapRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftSwapRequest", "id", id));
        if (!s.isActive()) {
            throw new ClinicalBusinessException("Swap row has been deactivated");
        }
        if (s.getStatus().isTerminal()) {
            throw new ClinicalBusinessException(
                    "Swap is already in terminal state " + s.getStatus());
        }
        return s;
    }

}
