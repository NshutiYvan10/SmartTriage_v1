package com.smartTriage.smartTriage_server.module.offline.repository;

import com.smartTriage.smartTriage_server.common.enums.SyncStatus;
import com.smartTriage.smartTriage_server.module.offline.entity.OfflineSyncRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OfflineSyncRecordRepository extends JpaRepository<OfflineSyncRecord, UUID> {

    Optional<OfflineSyncRecord> findByIdAndIsActiveTrue(UUID id);

    List<OfflineSyncRecord> findByClientDeviceIdAndSyncStatusAndIsActiveTrueOrderByCreatedOfflineAtAsc(
            String clientDeviceId, SyncStatus syncStatus);

    @Query("SELECT r FROM OfflineSyncRecord r WHERE r.hospital.id = :hospitalId " +
            "AND r.syncStatus = 'CONFLICT' AND r.isActive = true " +
            "ORDER BY r.createdOfflineAt DESC")
    List<OfflineSyncRecord> findUnresolvedConflicts(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT COUNT(r) FROM OfflineSyncRecord r WHERE r.hospital.id = :hospitalId " +
            "AND r.syncStatus = 'PENDING' AND r.isActive = true")
    long countPendingByHospital(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT COUNT(r) FROM OfflineSyncRecord r WHERE r.hospital.id = :hospitalId " +
            "AND r.syncStatus = 'CONFLICT' AND r.isActive = true")
    long countConflictsByHospital(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT COUNT(r) FROM OfflineSyncRecord r WHERE r.hospital.id = :hospitalId " +
            "AND r.syncStatus = 'SYNCED' AND r.isActive = true")
    long countSyncedByHospital(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT COUNT(r) FROM OfflineSyncRecord r WHERE r.hospital.id = :hospitalId " +
            "AND r.syncStatus = 'FAILED' AND r.isActive = true")
    long countFailedByHospital(@Param("hospitalId") UUID hospitalId);
}
