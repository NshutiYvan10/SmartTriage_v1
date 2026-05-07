package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.LeaveStatus;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.shift.dto.StaffLeaveDtos;
import com.smartTriage.smartTriage_server.module.shift.entity.StaffLeave;
import com.smartTriage.smartTriage_server.module.shift.mapper.StaffLeaveMapper;
import com.smartTriage.smartTriage_server.module.shift.repository.StaffLeaveRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Lifecycle for {@link StaffLeave} rows.
 *
 * <p>Two creation paths:
 * <ol>
 *   <li><b>Self-service</b> — a staff member submits their own leave (no
 *       {@code userId} in the request). Lands as REQUESTED.</li>
 *   <li><b>CN/admin behalf</b> — a CN files leave for someone else.
 *       May land as REQUESTED or, with {@code autoApprove=true}, directly
 *       as APPROVED (used for retroactive sick leave).</li>
 * </ol>
 *
 * <p>Decision rules:
 * <ul>
 *   <li>Approve / reject is restricted to CN-tier authority. The check is
 *       in the <em>controller</em> via {@code @shiftAssignmentAuthz} — this
 *       service trusts its caller.</li>
 *   <li>Cancel: requester or CN-tier may cancel.</li>
 *   <li>Once REJECTED or CANCELLED, status is terminal. APPROVED can still
 *       be CANCELLED (covers "I'm cutting my leave short").</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffLeaveService {

    private final StaffLeaveRepository leaveRepository;
    private final UserRepository userRepository;

    @Transactional
    public StaffLeaveDtos.Response create(
            User actor, StaffLeaveDtos.CreateRequest request, boolean actorHasApprovalAuthority) {

        // Resolve target user — defaults to the actor when not specified.
        UUID targetUserId = request.getUserId() != null ? request.getUserId() : actor.getId();
        User target = userRepository.findByIdAndIsActiveTrue(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", targetUserId));

        if (target.getHospital() == null) {
            throw new ClinicalBusinessException("Target user has no hospital assigned");
        }
        Hospital hospital = target.getHospital();

        // Filing on behalf of someone else requires CN/admin authority.
        if (!targetUserId.equals(actor.getId()) && !actorHasApprovalAuthority) {
            throw new ClinicalBusinessException(
                    "You may only request leave for yourself");
        }

        if (request.getEndsOn().isBefore(request.getStartsOn())) {
            throw new ClinicalBusinessException(
                    "Leave end date cannot be before the start date");
        }

        StaffLeave row = StaffLeave.builder()
                .user(target)
                .hospital(hospital)
                .leaveType(request.getLeaveType())
                .startsOn(request.getStartsOn())
                .endsOn(request.getEndsOn())
                .reason(request.getReason())
                .requestedAt(Instant.now())
                .requestedBy(actor)
                .leaveStatus(LeaveStatus.REQUESTED)
                .build();

        // Retroactive / immediate auto-approval (CN files sick leave).
        if (request.isAutoApprove() && actorHasApprovalAuthority) {
            row.setLeaveStatus(LeaveStatus.APPROVED);
            row.setApprovedAt(Instant.now());
            row.setApprovedBy(actor);
        }

        row = leaveRepository.save(row);
        log.info("Leave {} created for {} ({} → {}, type={}, status={})",
                row.getId(), target.getEmail(), row.getStartsOn(), row.getEndsOn(),
                row.getLeaveType(), row.getLeaveStatus());
        return StaffLeaveMapper.toResponse(row);
    }

    @Transactional
    public StaffLeaveDtos.Response approve(UUID leaveId, User actor, StaffLeaveDtos.DecisionRequest req) {
        StaffLeave row = loadActive(leaveId);
        if (row.getLeaveStatus() != LeaveStatus.REQUESTED) {
            throw new ClinicalBusinessException(
                    "Only REQUESTED leave can be approved (current: " + row.getLeaveStatus() + ")");
        }
        row.setLeaveStatus(LeaveStatus.APPROVED);
        row.setApprovedAt(Instant.now());
        row.setApprovedBy(actor);
        row = leaveRepository.save(row);
        log.info("Leave {} approved by {}", leaveId, actor.getEmail());
        return StaffLeaveMapper.toResponse(row);
    }

    @Transactional
    public StaffLeaveDtos.Response reject(UUID leaveId, User actor, StaffLeaveDtos.DecisionRequest req) {
        StaffLeave row = loadActive(leaveId);
        if (row.getLeaveStatus() != LeaveStatus.REQUESTED) {
            throw new ClinicalBusinessException(
                    "Only REQUESTED leave can be rejected (current: " + row.getLeaveStatus() + ")");
        }
        if (req == null || req.getNote() == null || req.getNote().isBlank()) {
            throw new ClinicalBusinessException("A rejection reason is required");
        }
        row.setLeaveStatus(LeaveStatus.REJECTED);
        row.setRejectedAt(Instant.now());
        row.setRejectedBy(actor);
        row.setRejectionReason(req.getNote());
        row = leaveRepository.save(row);
        log.info("Leave {} rejected by {}", leaveId, actor.getEmail());
        return StaffLeaveMapper.toResponse(row);
    }

    @Transactional
    public StaffLeaveDtos.Response cancel(UUID leaveId, User actor, boolean actorHasApprovalAuthority) {
        StaffLeave row = loadActive(leaveId);

        boolean isOwner = row.getUser() != null && row.getUser().getId().equals(actor.getId());
        boolean isAdmin = actor.getRole() == Role.HOSPITAL_ADMIN || actor.getRole() == Role.SUPER_ADMIN;

        if (!isOwner && !actorHasApprovalAuthority && !isAdmin) {
            throw new ClinicalBusinessException(
                    "You may only cancel your own leave, or one you have approval authority over");
        }

        if (row.getLeaveStatus() == LeaveStatus.CANCELLED
                || row.getLeaveStatus() == LeaveStatus.REJECTED) {
            throw new ClinicalBusinessException(
                    "Leave is already " + row.getLeaveStatus());
        }

        row.setLeaveStatus(LeaveStatus.CANCELLED);
        row.setCancelledAt(Instant.now());
        row.setCancelledBy(actor);
        row = leaveRepository.save(row);
        log.info("Leave {} cancelled by {}", leaveId, actor.getEmail());
        return StaffLeaveMapper.toResponse(row);
    }

    public List<StaffLeaveDtos.Response> listPending(UUID hospitalId) {
        return leaveRepository
                .findByHospitalIdAndLeaveStatusAndIsActiveTrueOrderByRequestedAtAsc(
                        hospitalId, LeaveStatus.REQUESTED)
                .stream()
                .map(StaffLeaveMapper::toResponse)
                .toList();
    }

    public List<StaffLeaveDtos.Response> listForUser(UUID userId) {
        return leaveRepository.findByUserIdAndIsActiveTrueOrderByStartsOnDesc(userId)
                .stream()
                .map(StaffLeaveMapper::toResponse)
                .toList();
    }

    public List<StaffLeaveDtos.Response> listOverlapping(
            UUID hospitalId, LocalDate rangeStart, LocalDate rangeEnd) {
        return leaveRepository.findApprovedOverlapping(hospitalId, rangeStart, rangeEnd)
                .stream()
                .map(StaffLeaveMapper::toResponse)
                .toList();
    }

    /** True if the user is on approved leave on the given date. */
    public boolean isUserOnLeave(UUID userId, LocalDate date) {
        return !leaveRepository.findApprovedCovering(userId, date).isEmpty();
    }

    private StaffLeave loadActive(UUID leaveId) {
        StaffLeave row = leaveRepository.findById(leaveId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffLeave", "id", leaveId));
        if (!row.isActive()) {
            throw new ClinicalBusinessException("Leave row has been deactivated");
        }
        return row;
    }
}
