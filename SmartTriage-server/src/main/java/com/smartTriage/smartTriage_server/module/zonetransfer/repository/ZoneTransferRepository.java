package com.smartTriage.smartTriage_server.module.zonetransfer.repository;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.zonetransfer.entity.ZoneTransfer;
import com.smartTriage.smartTriage_server.module.zonetransfer.entity.ZoneTransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ZoneTransferRepository extends JpaRepository<ZoneTransfer, UUID> {

    Optional<ZoneTransfer> findByIdAndIsActiveTrue(UUID id);

    /**
     * Existing pending transfer for a visit, if any. There can be at
     * most one PENDING_ACCEPT at a time per visit — used by the
     * auto re-triage path to either update the existing pending row
     * (target category got bumped further) or skip creation (the
     * pending one already covers it).
     */
    Optional<ZoneTransfer> findFirstByVisitIdAndStatusAndIsActiveTrueOrderByInitiatedAtDesc(
            UUID visitId, ZoneTransferStatus status);

    /**
     * All pending transfers across the hospital — drives the charge
     * nurse dashboard. Joined through visits.hospital_id since the
     * transfer itself is visit-scoped.
     */
    @Query("SELECT t FROM ZoneTransfer t JOIN FETCH t.visit v " +
            "JOIN FETCH v.patient LEFT JOIN FETCH v.currentBed " +
            "WHERE v.hospital.id = :hospitalId " +
            "AND t.status = 'PENDING_ACCEPT' AND t.isActive = true " +
            "ORDER BY t.initiatedAt ASC")
    List<ZoneTransfer> findPendingForHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Pending transfers into a specific zone — used to surface
     * incoming patients on the receiving zone's My Patients view.
     */
    @Query("SELECT t FROM ZoneTransfer t JOIN FETCH t.visit v " +
            "JOIN FETCH v.patient LEFT JOIN FETCH v.currentBed " +
            "WHERE v.hospital.id = :hospitalId " +
            "AND t.toZone = :zone " +
            "AND t.status = 'PENDING_ACCEPT' AND t.isActive = true " +
            "ORDER BY t.initiatedAt ASC")
    List<ZoneTransfer> findPendingIntoZone(
            @Param("hospitalId") UUID hospitalId,
            @Param("zone") EdZone zone);

    /**
     * History of completed/declined transfers for a visit — drives
     * the per-visit handover audit log.
     */
    @Query("SELECT t FROM ZoneTransfer t WHERE t.visit.id = :visitId " +
            "AND t.isActive = true ORDER BY t.initiatedAt DESC")
    List<ZoneTransfer> findHistoryForVisit(@Param("visitId") UUID visitId);

    /**
     * Movement history for a visit, oldest first — drives the handover
     * report's ED-timeline zone-transfer trail (chronological is the right
     * order for a timeline, unlike {@link #findHistoryForVisit} which is DESC).
     */
    List<ZoneTransfer> findByVisitIdAndIsActiveTrueOrderByInitiatedAtAsc(UUID visitId);
}
