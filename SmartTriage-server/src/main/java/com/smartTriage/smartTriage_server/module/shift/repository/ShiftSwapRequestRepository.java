package com.smartTriage.smartTriage_server.module.shift.repository;

import com.smartTriage.smartTriage_server.common.enums.SwapStatus;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftSwapRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShiftSwapRequestRepository extends JpaRepository<ShiftSwapRequest, UUID> {

    /**
     * Open swap requests where the given user is either the requester or
     * the named partner. Powers the "My swap requests" list in the
     * self-service /my-schedule view.
     */
    @Query("""
            SELECT s FROM ShiftSwapRequest s
             WHERE s.isActive = true
               AND s.status IN ('REQUESTED', 'PENDING_PARTNER_ACCEPT', 'PENDING_CHARGE_APPROVAL')
               AND (s.requesterUser.id = :userId OR s.partnerUser.id = :userId)
             ORDER BY s.createdAt DESC
            """)
    List<ShiftSwapRequest> findOpenForUser(@Param("userId") UUID userId);

    /**
     * Approval queue for the Charge Nurse — every swap awaiting a CN
     * decision at this hospital, oldest-first.
     */
    @Query("""
            SELECT s FROM ShiftSwapRequest s
             WHERE s.hospital.id = :hospitalId
               AND s.status = 'PENDING_CHARGE_APPROVAL'
               AND s.isActive = true
             ORDER BY s.createdAt ASC
            """)
    List<ShiftSwapRequest> findPendingChargeApprovalAtHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Is there an open swap on the given assignment, on either side?
     * The unique indices already prevent inserting a second one — this is
     * the read counterpart used by the service before persisting a new
     * request to give a clean error message instead of a SQL violation.
     */
    @Query("""
            SELECT s FROM ShiftSwapRequest s
             WHERE (s.requesterAssignment.id = :assignmentId
                    OR s.partnerAssignment.id = :assignmentId)
               AND s.isActive = true
               AND s.status IN ('REQUESTED', 'PENDING_PARTNER_ACCEPT', 'PENDING_CHARGE_APPROVAL')
            """)
    Optional<ShiftSwapRequest> findOpenForAssignment(@Param("assignmentId") UUID assignmentId);

    /** History view — every swap a user has ever participated in. */
    @Query("""
            SELECT s FROM ShiftSwapRequest s
             WHERE (s.requesterUser.id = :userId OR s.partnerUser.id = :userId)
               AND s.isActive = true
             ORDER BY s.createdAt DESC
            """)
    List<ShiftSwapRequest> findHistoryForUser(@Param("userId") UUID userId);

    long countByHospitalIdAndStatusAndIsActiveTrue(UUID hospitalId, SwapStatus status);
}
