package com.smartTriage.smartTriage_server.module.lab.repository;

import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabOrderRepository extends JpaRepository<LabOrder, UUID> {

    Optional<LabOrder> findByIdAndIsActiveTrue(UUID id);

    Optional<LabOrder> findByOrderNumberAndIsActiveTrue(String orderNumber);

    Page<LabOrder> findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(
            UUID visitId, Pageable pageable);

    /**
     * Pending (not yet resulted and not cancelled) lab orders for a hospital.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true AND o.resultedAt IS NULL AND o.cancelledAt IS NULL " +
            "ORDER BY CASE o.priority WHEN 'STAT' THEN 0 WHEN 'URGENT' THEN 1 ELSE 2 END, o.orderedAt ASC")
    Page<LabOrder> findPendingOrders(
            @Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Unacknowledged critical results for a hospital.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true AND o.isCritical = true " +
            "AND o.criticalValueAcknowledgedAt IS NULL " +
            "ORDER BY o.resultedAt ASC")
    List<LabOrder> findUnacknowledgedCriticalResults(
            @Param("hospitalId") UUID hospitalId);

    /**
     * Active STAT orders (not yet resulted and not cancelled) for a hospital.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true AND o.priority = 'STAT' " +
            "AND o.resultedAt IS NULL AND o.cancelledAt IS NULL " +
            "ORDER BY o.orderedAt ASC")
    List<LabOrder> findActiveStatOrders(
            @Param("hospitalId") UUID hospitalId);

    /**
     * Overdue STAT orders — ordered more than 30 minutes ago without result.
     */
    @Query("SELECT o FROM LabOrder o WHERE o.isActive = true AND o.priority = :priority " +
            "AND o.resultedAt IS NULL AND o.cancelledAt IS NULL " +
            "AND o.orderedAt < :cutoff")
    List<LabOrder> findOverdueOrdersByPriority(
            @Param("priority") LabPriority priority,
            @Param("cutoff") Instant cutoff);

    /**
     * Critical results not acknowledged within the cutoff time.
     */
    @Query("SELECT o FROM LabOrder o WHERE o.isActive = true AND o.isCritical = true " +
            "AND o.criticalValueAcknowledgedAt IS NULL " +
            "AND o.resultedAt IS NOT NULL AND o.resultedAt < :cutoff")
    List<LabOrder> findUnacknowledgedCriticalResultsBefore(
            @Param("cutoff") Instant cutoff);

    /**
     * Count for order number generation — orders placed today.
     */
    @Query("SELECT COUNT(o) FROM LabOrder o WHERE o.orderNumber LIKE :prefix%")
    long countByOrderNumberPrefix(@Param("prefix") String prefix);
}
