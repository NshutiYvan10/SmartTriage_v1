package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.shift.dto.ChargeNurseDelegationDtos;
import com.smartTriage.smartTriage_server.module.shift.entity.ChargeNurseDelegation;
import com.smartTriage.smartTriage_server.module.shift.mapper.ChargeNurseDelegationMapper;
import com.smartTriage.smartTriage_server.module.shift.repository.ChargeNurseDelegationRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lifecycle for {@link ChargeNurseDelegation} rows.
 *
 * <p>Authority model:
 * <ul>
 *   <li>Only the on-duty Charge Nurse for a hospital may create a delegation
 *       on their own behalf. (HOSPITAL_ADMIN can also create one — used when
 *       the CN themselves is incapacitated and a managerial decision is
 *       needed to fill the gap.)</li>
 *   <li>Only the original delegating CN, the delegate, or HOSPITAL_ADMIN /
 *       SUPER_ADMIN may revoke a delegation. The delegate revoking
 *       represents "I'm handing the hat back" — common when the CN returns
 *       earlier than planned.</li>
 * </ul>
 *
 * <p>Validation enforced here (in addition to the SQL CHECKs):
 * <ul>
 *   <li>Delegate must have role {@code NURSE} — defence-in-depth so the
 *       authz layer can rely on it without re-querying.</li>
 *   <li>Delegate and delegating CN must belong to the same hospital.</li>
 *   <li>{@code endsAt} (when present) must lie in the future at create time.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChargeNurseDelegationService {

    private final ChargeNurseDelegationRepository delegationRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;

    /**
     * Create a new acting-CN delegation. The {@code delegatingUser} is
     * resolved from the security principal at the controller layer and
     * passed in here to keep this service free of Spring Security deps.
     */
    @Transactional
    public ChargeNurseDelegationDtos.Response create(
            UUID hospitalId,
            User delegatingUser,
            ChargeNurseDelegationDtos.CreateRequest request) {

        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        User delegate = userRepository.findByIdAndIsActiveTrue(request.getDelegateUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.getDelegateUserId()));

        validateDelegate(delegate, hospitalId);

        if (delegatingUser.getId().equals(delegate.getId())) {
            throw new ClinicalBusinessException(
                    "A Charge Nurse cannot delegate authority to themselves");
        }

        if (request.getEndsAt() != null && !request.getEndsAt().isAfter(request.getStartsAt())) {
            throw new ClinicalBusinessException(
                    "Delegation end time must be after the start time");
        }
        if (request.getEndsAt() != null && request.getEndsAt().isBefore(Instant.now())) {
            throw new ClinicalBusinessException(
                    "Delegation end time must be in the future");
        }

        ChargeNurseDelegation row = ChargeNurseDelegation.builder()
                .hospital(hospital)
                .delegatingUser(delegatingUser)
                .delegate(delegate)
                .startsAt(request.getStartsAt())
                .endsAt(request.getEndsAt())
                .reason(request.getReason())
                .build();

        row = delegationRepository.save(row);
        log.info("Charge-nurse delegation created: {} → {} at {} (window {} → {})",
                delegatingUser.getEmail(), delegate.getEmail(),
                hospital.getName(), row.getStartsAt(), row.getEndsAt());
        return ChargeNurseDelegationMapper.toResponse(row);
    }

    @Transactional
    public ChargeNurseDelegationDtos.Response revoke(
            UUID delegationId,
            User actor,
            ChargeNurseDelegationDtos.RevokeRequest request) {

        ChargeNurseDelegation row = delegationRepository.findById(delegationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ChargeNurseDelegation", "id", delegationId));

        if (row.getRevokedAt() != null) {
            throw new ClinicalBusinessException("Delegation is already revoked");
        }
        if (!row.isActive()) {
            throw new ClinicalBusinessException("Delegation has been deactivated");
        }

        // Authority: delegating CN, the delegate themselves, hospital admin,
        // or super admin. The controller has already resolved sameHospital
        // for non-super-admin cases.
        boolean isParticipant = actor.getId().equals(row.getDelegatingUser().getId())
                || actor.getId().equals(row.getDelegate().getId());
        boolean isAdmin = actor.getRole() == Role.HOSPITAL_ADMIN
                || actor.getRole() == Role.SUPER_ADMIN;
        if (!isParticipant && !isAdmin) {
            throw new ClinicalBusinessException(
                    "Only the delegating CN, the acting CN, or a hospital admin may revoke this delegation");
        }

        row.setRevokedAt(Instant.now());
        row.setRevokedBy(actor);
        row.setRevocationReason(request != null ? request.getRevocationReason() : null);
        row = delegationRepository.save(row);

        log.info("Charge-nurse delegation {} revoked by {} (reason: {})",
                delegationId, actor.getEmail(), row.getRevocationReason());
        return ChargeNurseDelegationMapper.toResponse(row);
    }

    public List<ChargeNurseDelegationDtos.Response> listActive(UUID hospitalId) {
        return delegationRepository.findActiveAtHospital(hospitalId, Instant.now())
                .stream()
                .map(ChargeNurseDelegationMapper::toResponse)
                .toList();
    }

    public List<ChargeNurseDelegationDtos.Response> listIssuedBy(UUID delegatingUserId) {
        return delegationRepository.findByDelegatingUserIdOrderByStartsAtDesc(delegatingUserId)
                .stream()
                .map(ChargeNurseDelegationMapper::toResponse)
                .toList();
    }

    public List<ChargeNurseDelegationDtos.Response> listReceivedBy(UUID delegateId) {
        return delegationRepository.findByDelegateIdOrderByStartsAtDesc(delegateId)
                .stream()
                .map(ChargeNurseDelegationMapper::toResponse)
                .toList();
    }

    /* ─── helpers ─── */

    private void validateDelegate(User delegate, UUID hospitalId) {
        if (delegate.getRole() != Role.NURSE) {
            throw new ClinicalBusinessException(
                    "Only NURSE-role users may receive a Charge Nurse delegation");
        }
        if (delegate.getHospital() == null
                || !hospitalId.equals(delegate.getHospital().getId())) {
            throw new ClinicalBusinessException(
                    "Delegate must belong to the same hospital");
        }
        // Soft check: if the delegate already has CHARGE_NURSE designation,
        // delegation is redundant — they already carry the authority. Allow
        // it (might be a back-up CN), but flag it in the audit log.
        if (delegate.getDesignation() == Designation.CHARGE_NURSE) {
            log.info("Note: delegate {} already has CHARGE_NURSE designation — "
                    + "delegation is redundant but accepted", delegate.getEmail());
        }
    }
}
