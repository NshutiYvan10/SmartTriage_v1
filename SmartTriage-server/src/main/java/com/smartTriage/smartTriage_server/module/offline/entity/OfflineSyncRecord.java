package com.smartTriage.smartTriage_server.module.offline.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.SyncOperationType;
import com.smartTriage.smartTriage_server.common.enums.SyncStatus;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * OfflineSyncRecord — tracks individual records that were created or modified offline
 * and need to be synced to the server.
 *
 * In Rwanda, connectivity can be intermittent especially in rural districts.
 * This entity enables the backend to process, validate, and resolve conflicts
 * from offline operations.
 */
@Entity
@Table(name = "offline_sync_records", indexes = {
        @Index(name = "idx_sync_hospital", columnList = "hospital_id"),
        @Index(name = "idx_sync_device", columnList = "client_device_id"),
        @Index(name = "idx_sync_status", columnList = "sync_status"),
        @Index(name = "idx_sync_entity_type", columnList = "entity_type"),
        @Index(name = "idx_sync_entity_id", columnList = "entity_id"),
        @Index(name = "idx_sync_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineSyncRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(name = "client_device_id", nullable = false)
    private String clientDeviceId;

    @Column(name = "client_device_name")
    private String clientDeviceName;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 10)
    private SyncOperationType operationType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 15)
    @Builder.Default
    private SyncStatus syncStatus = SyncStatus.PENDING;

    @Column(name = "conflict_resolution", columnDefinition = "TEXT")
    private String conflictResolution;

    @Column(name = "created_offline_at")
    private Instant createdOfflineAt;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
