package com.smartTriage.smartTriage_server.module.ems.repository;

import com.smartTriage.smartTriage_server.common.enums.EmsRunStatus;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmsRunRepository extends JpaRepository<EmsRun, UUID> {

    Optional<EmsRun> findByIdAndIsActiveTrue(UUID id);

    /**
     * Charge-nurse inbound board: runs en route or arrived but not
     * yet handed off. Sorted by ETA / dispatch so RED-likely cases
     * surface first.
     */
    @Query("SELECT r FROM EmsRun r " +
            "LEFT JOIN FETCH r.visit v LEFT JOIN FETCH v.patient " +
            "WHERE r.hospital.id = :hospitalId " +
            "AND r.isActive = true " +
            "AND r.status IN (com.smartTriage.smartTriage_server.common.enums.EmsRunStatus.EN_ROUTE, " +
            "                 com.smartTriage.smartTriage_server.common.enums.EmsRunStatus.ARRIVED) " +
            "ORDER BY CASE r.fieldTriageCategory " +
            "  WHEN 'RED' THEN 0 WHEN 'ORANGE' THEN 1 WHEN 'YELLOW' THEN 2 " +
            "  WHEN 'GREEN' THEN 3 WHEN 'BLUE' THEN 4 ELSE 5 END, " +
            "r.dispatchedAt ASC")
    List<EmsRun> findInbound(@Param("hospitalId") UUID hospitalId);

    /** A paramedic's recent runs (any status). */
    @Query("SELECT r FROM EmsRun r " +
            "LEFT JOIN FETCH r.visit v LEFT JOIN FETCH v.patient " +
            "WHERE r.paramedic.id = :paramedicId " +
            "AND r.isActive = true ORDER BY r.dispatchedAt DESC")
    List<EmsRun> findByParamedic(@Param("paramedicId") UUID paramedicId);

    /** Active (non-final) runs by paramedic. */
    @Query("SELECT r FROM EmsRun r " +
            "LEFT JOIN FETCH r.visit v LEFT JOIN FETCH v.patient " +
            "WHERE r.paramedic.id = :paramedicId " +
            "AND r.isActive = true " +
            "AND r.status NOT IN (com.smartTriage.smartTriage_server.common.enums.EmsRunStatus.HANDED_OFF, " +
            "                     com.smartTriage.smartTriage_server.common.enums.EmsRunStatus.CANCELLED) " +
            "ORDER BY r.dispatchedAt DESC")
    List<EmsRun> findActiveByParamedic(@Param("paramedicId") UUID paramedicId);

    Optional<EmsRun> findByVisitIdAndIsActiveTrue(UUID visitId);

    @Query("SELECT r FROM EmsRun r WHERE r.hospital.id = :hospitalId " +
            "AND r.isActive = true AND r.status = :status " +
            "ORDER BY r.dispatchedAt DESC")
    List<EmsRun> findByHospitalAndStatus(
            @Param("hospitalId") UUID hospitalId,
            @Param("status") EmsRunStatus status);

    /**
     * Background scheduler — find arrived runs that have been waiting
     * for a transfer-of-care ack longer than the threshold. Used to
     * raise EMS_HANDOVER_PENDING alerts.
     */
    @Query("SELECT r FROM EmsRun r WHERE r.isActive = true " +
            "AND r.status = com.smartTriage.smartTriage_server.common.enums.EmsRunStatus.ARRIVED " +
            "AND r.edArrivedAt < :cutoff")
    List<EmsRun> findArrivedAwaitingHandoverBefore(@Param("cutoff") Instant cutoff);
}
