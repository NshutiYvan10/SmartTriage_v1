package com.smartTriage.smartTriage_server.module.bed.repository;

import com.smartTriage.smartTriage_server.common.enums.BedStatus;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BedRepository extends JpaRepository<Bed, UUID> {

    Optional<Bed> findByIdAndIsActiveTrue(UUID id);

    /** All beds in a hospital, sorted by zone then displayOrder/code. */
    @Query("SELECT b FROM Bed b WHERE b.isActive = true AND b.hospital.id = :hospitalId " +
            "ORDER BY b.zone ASC, b.displayOrder ASC, b.code ASC")
    List<Bed> findAllByHospital(@Param("hospitalId") UUID hospitalId);

    /** All beds in a specific zone of a hospital, sorted by displayOrder/code. */
    @Query("SELECT b FROM Bed b WHERE b.isActive = true AND b.hospital.id = :hospitalId " +
            "AND b.zone = :zone ORDER BY b.displayOrder ASC, b.code ASC")
    List<Bed> findByHospitalAndZone(@Param("hospitalId") UUID hospitalId, @Param("zone") EdZone zone);

    /** All beds with a given status. */
    List<Bed> findByHospitalIdAndStatusAndIsActiveTrueOrderByDisplayOrderAsc(UUID hospitalId, BedStatus status);

    /** Find a bed by its code within a hospital. */
    Optional<Bed> findByHospitalIdAndCodeAndIsActiveTrue(UUID hospitalId, String code);

    /** The bed currently holding a given visit (if any). */
    Optional<Bed> findByCurrentVisitIdAndIsActiveTrue(UUID visitId);

    /** Available beds in a zone — for placement dropdowns. */
    @Query("SELECT b FROM Bed b WHERE b.isActive = true AND b.hospital.id = :hospitalId " +
            "AND b.zone = :zone AND b.status = 'AVAILABLE' AND b.currentVisit IS NULL " +
            "ORDER BY b.displayOrder ASC, b.code ASC")
    List<Bed> findAvailableInZone(@Param("hospitalId") UUID hospitalId, @Param("zone") EdZone zone);

    /** Count beds by status for a hospital (dashboard KPIs). */
    long countByHospitalIdAndStatusAndIsActiveTrue(UUID hospitalId, BedStatus status);

    /** Count beds by zone for a hospital. */
    long countByHospitalIdAndZoneAndIsActiveTrue(UUID hospitalId, EdZone zone);
}
