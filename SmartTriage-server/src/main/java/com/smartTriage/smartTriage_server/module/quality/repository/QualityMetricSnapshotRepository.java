package com.smartTriage.smartTriage_server.module.quality.repository;

import com.smartTriage.smartTriage_server.common.enums.MetricPeriod;
import com.smartTriage.smartTriage_server.module.quality.entity.QualityMetricSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QualityMetricSnapshotRepository extends JpaRepository<QualityMetricSnapshot, UUID> {

    Optional<QualityMetricSnapshot> findByIdAndIsActiveTrue(UUID id);

    Optional<QualityMetricSnapshot> findByHospitalIdAndSnapshotDateAndSnapshotPeriodAndIsActiveTrue(
            UUID hospitalId, LocalDate snapshotDate, MetricPeriod snapshotPeriod);

    @Query("SELECT q FROM QualityMetricSnapshot q WHERE q.hospital.id = :hospitalId " +
            "AND q.isActive = true AND q.snapshotDate = :date AND q.snapshotPeriod = 'DAILY'")
    Optional<QualityMetricSnapshot> findDailyByHospitalAndDate(
            @Param("hospitalId") UUID hospitalId,
            @Param("date") LocalDate date);

    @Query("SELECT q FROM QualityMetricSnapshot q WHERE q.hospital.id = :hospitalId " +
            "AND q.isActive = true AND q.snapshotDate BETWEEN :fromDate AND :toDate " +
            "ORDER BY q.snapshotDate ASC")
    List<QualityMetricSnapshot> findByHospitalAndDateRange(
            @Param("hospitalId") UUID hospitalId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    @Query("SELECT q FROM QualityMetricSnapshot q WHERE q.hospital.id = :hospitalId " +
            "AND q.isActive = true AND q.snapshotPeriod = :period " +
            "ORDER BY q.snapshotDate DESC")
    List<QualityMetricSnapshot> findTrends(
            @Param("hospitalId") UUID hospitalId,
            @Param("period") MetricPeriod period,
            Pageable pageable);

    @Query("SELECT q FROM QualityMetricSnapshot q WHERE q.hospital.id = :hospitalId " +
            "AND q.isActive = true AND q.snapshotPeriod = 'DAILY' " +
            "AND q.snapshotDate BETWEEN :fromDate AND :toDate " +
            "ORDER BY q.snapshotDate ASC")
    List<QualityMetricSnapshot> findDailySnapshotsInRange(
            @Param("hospitalId") UUID hospitalId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * Average total patients for a hospital on a given day of week over the last N daily snapshots.
     */
    @Query("SELECT AVG(q.totalPatients) FROM QualityMetricSnapshot q " +
            "WHERE q.hospital.id = :hospitalId AND q.isActive = true " +
            "AND q.snapshotPeriod = 'DAILY' AND q.snapshotDate >= :sinceDate")
    Double findAverageTotalPatientsSince(
            @Param("hospitalId") UUID hospitalId,
            @Param("sinceDate") LocalDate sinceDate);
}
