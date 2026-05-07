package com.smartTriage.smartTriage_server.module.shift.repository;

import com.smartTriage.smartTriage_server.common.enums.LeaveStatus;
import com.smartTriage.smartTriage_server.module.shift.entity.StaffLeave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface StaffLeaveRepository extends JpaRepository<StaffLeave, UUID> {

    /**
     * "Is this user blocked from being scheduled on this date by approved
     * leave?" Backed by {@code idx_staff_leave_user_window}.
     */
    @Query("""
            SELECT sl FROM StaffLeave sl
             WHERE sl.user.id = :userId
               AND sl.leaveStatus = 'APPROVED'
               AND sl.isActive = true
               AND sl.startsOn <= :date
               AND sl.endsOn   >= :date
            """)
    List<StaffLeave> findApprovedCovering(
            @Param("userId") UUID userId,
            @Param("date") LocalDate date);

    /**
     * Coverage-map feed: every approved leave at this hospital that
     * overlaps the given window.
     */
    @Query("""
            SELECT sl FROM StaffLeave sl
             WHERE sl.hospital.id = :hospitalId
               AND sl.leaveStatus = 'APPROVED'
               AND sl.isActive = true
               AND sl.startsOn <= :rangeEnd
               AND sl.endsOn   >= :rangeStart
             ORDER BY sl.startsOn
            """)
    List<StaffLeave> findApprovedOverlapping(
            @Param("hospitalId") UUID hospitalId,
            @Param("rangeStart") LocalDate rangeStart,
            @Param("rangeEnd") LocalDate rangeEnd);

    /** Pending-approval queue for a hospital, oldest-first. */
    List<StaffLeave> findByHospitalIdAndLeaveStatusAndIsActiveTrueOrderByRequestedAtAsc(
            UUID hospitalId, LeaveStatus status);

    /** This user's leave history, newest-first. */
    List<StaffLeave> findByUserIdAndIsActiveTrueOrderByStartsOnDesc(UUID userId);
}
