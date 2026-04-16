package com.smartTriage.smartTriage_server.module.safety.repository;

import com.smartTriage.smartTriage_server.common.enums.IncidentType;
import com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident;
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
public interface SafetyIncidentRepository extends JpaRepository<SafetyIncident, UUID> {

    Optional<SafetyIncident> findByIdAndIsActiveTrue(UUID id);

    Page<SafetyIncident> findByHospitalIdAndIsActiveTrueOrderByIncidentDateTimeDesc(
            UUID hospitalId, Pageable pageable);

    Page<SafetyIncident> findByHospitalIdAndIncidentTypeAndIsActiveTrueOrderByIncidentDateTimeDesc(
            UUID hospitalId, IncidentType type, Pageable pageable);

    @Query("SELECT i FROM SafetyIncident i WHERE i.hospital.id = :hospitalId " +
            "AND i.isActive = true AND i.status != 'CLOSED' " +
            "ORDER BY i.incidentDateTime DESC")
    List<SafetyIncident> findOpenIncidents(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT COUNT(i) FROM SafetyIncident i WHERE i.hospital.id = :hospitalId " +
            "AND i.isActive = true " +
            "AND i.incidentDateTime BETWEEN :from AND :to")
    long countByHospitalAndDateRange(
            @Param("hospitalId") UUID hospitalId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("SELECT i.incidentType, COUNT(i) FROM SafetyIncident i " +
            "WHERE i.hospital.id = :hospitalId AND i.isActive = true " +
            "AND i.incidentDateTime BETWEEN :from AND :to " +
            "GROUP BY i.incidentType ORDER BY COUNT(i) DESC")
    List<Object[]> countByTypeAndDateRange(
            @Param("hospitalId") UUID hospitalId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("SELECT i.severity, COUNT(i) FROM SafetyIncident i " +
            "WHERE i.hospital.id = :hospitalId AND i.isActive = true " +
            "AND i.incidentDateTime BETWEEN :from AND :to " +
            "GROUP BY i.severity ORDER BY COUNT(i) DESC")
    List<Object[]> countBySeverityAndDateRange(
            @Param("hospitalId") UUID hospitalId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Count the latest incident number prefix for auto-generation.
     */
    @Query("SELECT COUNT(i) FROM SafetyIncident i WHERE i.incidentNumber LIKE :prefix%")
    long countByIncidentNumberPrefix(@Param("prefix") String prefix);
}
