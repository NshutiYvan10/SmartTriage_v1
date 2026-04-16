package com.smartTriage.smartTriage_server.module.offline.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * SystemHealthStatus — captures a snapshot of system health at a point in time.
 *
 * Tracks server connectivity, database availability, internet status, and power status.
 * Critical for monitoring rural health facilities where infrastructure is unreliable.
 */
@Entity
@Table(name = "system_health_statuses", indexes = {
        @Index(name = "idx_health_hospital", columnList = "hospital_id"),
        @Index(name = "idx_health_check_time", columnList = "check_time"),
        @Index(name = "idx_health_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemHealthStatus extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(name = "check_time")
    private Instant checkTime;

    @Column(name = "server_online", nullable = false)
    @Builder.Default
    private boolean serverOnline = true;

    @Column(name = "database_online", nullable = false)
    @Builder.Default
    private boolean databaseOnline = true;

    @Column(name = "internet_connectivity", nullable = false)
    @Builder.Default
    private boolean internetConnectivity = true;

    @Column(name = "power_status", length = 20)
    private String powerStatus;

    @Column(name = "last_successful_sync")
    private Instant lastSuccessfulSync;

    @Column(name = "pending_sync_count", nullable = false)
    @Builder.Default
    private int pendingSyncCount = 0;

    @Column(name = "active_offline_devices", nullable = false)
    @Builder.Default
    private int activeOfflineDevices = 0;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
