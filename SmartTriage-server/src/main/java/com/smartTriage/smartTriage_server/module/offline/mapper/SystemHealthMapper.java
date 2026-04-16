package com.smartTriage.smartTriage_server.module.offline.mapper;

import com.smartTriage.smartTriage_server.module.offline.dto.SystemHealthResponse;
import com.smartTriage.smartTriage_server.module.offline.entity.SystemHealthStatus;

/**
 * Mapper for SystemHealthStatus entity to response DTO.
 */
public final class SystemHealthMapper {

    private SystemHealthMapper() {
    }

    public static SystemHealthResponse toResponse(SystemHealthStatus status) {
        SystemHealthResponse.SystemHealthResponseBuilder builder = SystemHealthResponse.builder()
                .id(status.getId())
                .checkTime(status.getCheckTime())
                .serverOnline(status.isServerOnline())
                .databaseOnline(status.isDatabaseOnline())
                .internetConnectivity(status.isInternetConnectivity())
                .powerStatus(status.getPowerStatus())
                .lastSuccessfulSync(status.getLastSuccessfulSync())
                .pendingSyncCount(status.getPendingSyncCount())
                .activeOfflineDevices(status.getActiveOfflineDevices())
                .notes(status.getNotes())
                .createdAt(status.getCreatedAt());

        if (status.getHospital() != null) {
            builder.hospitalId(status.getHospital().getId());
            builder.hospitalName(status.getHospital().getName());
        }

        return builder.build();
    }
}
