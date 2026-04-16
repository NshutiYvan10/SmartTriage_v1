package com.smartTriage.smartTriage_server.module.reporting.repository;

import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MohReportRepository extends JpaRepository<MohReport, UUID> {

    Optional<MohReport> findByIdAndIsActiveTrue(UUID id);

    Page<MohReport> findByHospitalIdAndIsActiveTrueOrderByReportPeriodStartDesc(
            UUID hospitalId, Pageable pageable);

    @Query("SELECT r FROM MohReport r WHERE r.hospital.id = :hospitalId " +
            "AND r.reportType = :reportType " +
            "AND r.reportPeriodStart = :periodStart " +
            "AND r.reportPeriodEnd = :periodEnd " +
            "AND r.isActive = true")
    Optional<MohReport> findByHospitalAndTypeAndPeriod(
            @Param("hospitalId") UUID hospitalId,
            @Param("reportType") MohReportType reportType,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);

    @Query("SELECT r FROM MohReport r WHERE r.hospital.id = :hospitalId " +
            "AND r.reportType = :reportType " +
            "AND r.isActive = true " +
            "ORDER BY r.reportPeriodStart DESC")
    Page<MohReport> findByHospitalAndType(
            @Param("hospitalId") UUID hospitalId,
            @Param("reportType") MohReportType reportType,
            Pageable pageable);
}
