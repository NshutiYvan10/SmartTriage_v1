package com.smartTriage.smartTriage_server.module.icu.repository;

import com.smartTriage.smartTriage_server.common.enums.IcuEscalationStatus;
import com.smartTriage.smartTriage_server.module.icu.entity.IcuEscalation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IcuEscalationRepository extends JpaRepository<IcuEscalation, UUID> {

    Optional<IcuEscalation> findByIdAndIsActiveTrue(UUID id);

    Optional<IcuEscalation> findByVisitIdAndIsActiveTrue(UUID visitId);

    /**
     * Find all active escalations for a hospital that are not yet resolved.
     * Excludes TRANSFERRED_TO_ICU and CANCELLED as those are terminal states.
     */
    @Query("SELECT e FROM IcuEscalation e JOIN e.visit v WHERE v.hospital.id = :hospitalId " +
            "AND e.isActive = true " +
            "AND e.status NOT IN (com.smartTriage.smartTriage_server.common.enums.IcuEscalationStatus.TRANSFERRED_TO_ICU, " +
            "com.smartTriage.smartTriage_server.common.enums.IcuEscalationStatus.CANCELLED) " +
            "ORDER BY e.escalatedAt DESC")
    Page<IcuEscalation> findActiveEscalationsByHospital(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    long countByStatusAndVisitHospitalId(IcuEscalationStatus status, UUID hospitalId);

    /**
     * Check if a visit already has a non-terminal active escalation.
     * Used to prevent duplicate escalation creation.
     */
    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM IcuEscalation e " +
            "WHERE e.visit.id = :visitId AND e.isActive = true " +
            "AND e.status NOT IN (com.smartTriage.smartTriage_server.common.enums.IcuEscalationStatus.TRANSFERRED_TO_ICU, " +
            "com.smartTriage.smartTriage_server.common.enums.IcuEscalationStatus.CANCELLED)")
    boolean existsActiveEscalationForVisit(@Param("visitId") UUID visitId);

    /**
     * Batched lookup for the patient-card "ICU pending" badge — returns
     * the visit IDs (from the supplied set) that currently have a
     * non-terminal active escalation. Caller treats absent visits as
     * "no open escalation". One DB round trip regardless of page size.
     */
    @Query("SELECT DISTINCT e.visit.id FROM IcuEscalation e " +
            "WHERE e.visit.id IN :visitIds AND e.isActive = true " +
            "AND e.status NOT IN (com.smartTriage.smartTriage_server.common.enums.IcuEscalationStatus.TRANSFERRED_TO_ICU, " +
            "com.smartTriage.smartTriage_server.common.enums.IcuEscalationStatus.CANCELLED)")
    List<UUID> findVisitIdsWithOpenEscalation(@Param("visitIds") java.util.Collection<UUID> visitIds);
}
