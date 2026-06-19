package com.smartTriage.smartTriage_server.module.reporting.repository;

import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import com.smartTriage.smartTriage_server.common.enums.ReportLevel;
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

    /** National rollups (and any other level) regardless of hospital — for the SUPER_ADMIN national view. */
    Page<MohReport> findByReportLevelAndIsActiveTrueOrderByReportPeriodStartDesc(
            ReportLevel reportLevel, Pageable pageable);

    /** Object-level authz projection: report level for an active report (empty if unknown/inactive). */
    @Query("SELECT r.reportLevel FROM MohReport r WHERE r.id = :id AND r.isActive = true")
    Optional<ReportLevel> findReportLevelById(@Param("id") UUID id);

    /** Object-level authz projection: owning hospital id for an active HOSPITAL-level report
     * (empty for a NATIONAL report — its hospital is null — or an unknown/inactive id). */
    @Query("SELECT r.hospital.id FROM MohReport r WHERE r.id = :id AND r.isActive = true")
    Optional<UUID> findHospitalIdById(@Param("id") UUID id);

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
