package com.smartTriage.smartTriage_server.module.handover.repository;

import com.smartTriage.smartTriage_server.common.enums.HandoverReportType;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
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
public interface HandoverReportRepository extends JpaRepository<HandoverReport, UUID> {

    Optional<HandoverReport> findByIdAndIsActiveTrue(UUID id);

    List<HandoverReport> findByVisitIdAndIsActiveTrueOrderByGeneratedAtDesc(UUID visitId);

    @Query("SELECT h FROM HandoverReport h WHERE h.hospital.id = :hospitalId " +
            "AND h.isActive = true AND h.generatedAt BETWEEN :shiftStart AND :shiftEnd " +
            "ORDER BY h.generatedAt DESC")
    List<HandoverReport> findReportsForShift(
            @Param("hospitalId") UUID hospitalId,
            @Param("shiftStart") Instant shiftStart,
            @Param("shiftEnd") Instant shiftEnd);

    @Query("SELECT h FROM HandoverReport h WHERE h.hospital.id = :hospitalId " +
            "AND h.isActive = true AND h.reportType = :reportType " +
            "ORDER BY h.generatedAt DESC")
    Page<HandoverReport> findByHospitalAndType(
            @Param("hospitalId") UUID hospitalId,
            @Param("reportType") HandoverReportType reportType,
            Pageable pageable);
}
