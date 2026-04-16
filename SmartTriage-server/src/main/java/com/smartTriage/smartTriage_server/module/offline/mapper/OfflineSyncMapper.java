package com.smartTriage.smartTriage_server.module.offline.mapper;

import com.smartTriage.smartTriage_server.module.offline.dto.OfflineSyncRecordResponse;
import com.smartTriage.smartTriage_server.module.offline.entity.OfflineSyncRecord;

/**
 * Mapper for OfflineSyncRecord entity to response DTO.
 */
public final class OfflineSyncMapper {

    private OfflineSyncMapper() {
    }

    public static OfflineSyncRecordResponse toResponse(OfflineSyncRecord record) {
        OfflineSyncRecordResponse.OfflineSyncRecordResponseBuilder builder = OfflineSyncRecordResponse.builder()
                .id(record.getId())
                .clientDeviceId(record.getClientDeviceId())
                .clientDeviceName(record.getClientDeviceName())
                .entityType(record.getEntityType())
                .entityId(record.getEntityId())
                .operationType(record.getOperationType())
                .payload(record.getPayload())
                .syncStatus(record.getSyncStatus())
                .conflictResolution(record.getConflictResolution())
                .createdOfflineAt(record.getCreatedOfflineAt())
                .syncedAt(record.getSyncedAt())
                .errorMessage(record.getErrorMessage())
                .createdAt(record.getCreatedAt());

        if (record.getHospital() != null) {
            builder.hospitalId(record.getHospital().getId());
        }

        return builder.build();
    }
}
