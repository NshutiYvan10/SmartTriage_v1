package com.smartTriage.smartTriage_server.module.bed.mapper;

import com.smartTriage.smartTriage_server.module.bed.dto.BedResponse;
import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;

import java.util.UUID;

/**
 * Maps Bed entities to response DTOs. Takes the associated IoTDevice and
 * active session id as hints so callers can batch-fetch these once per
 * zone query instead of lazy-loading per bed.
 */
public final class BedMapper {

    private BedMapper() {
    }

    public static BedResponse toResponse(Bed bed, IoTDevice assignedDevice, UUID activeSessionId) {
        BedResponse.BedResponseBuilder b = BedResponse.builder()
                .id(bed.getId())
                .hospitalId(bed.getHospital().getId())
                .zone(bed.getZone())
                .code(bed.getCode())
                .label(bed.getLabel())
                .status(bed.getStatus())
                .hasMonitor(bed.isHasMonitor())
                .displayOrder(bed.getDisplayOrder())
                .notes(bed.getNotes())
                .createdAt(bed.getCreatedAt())
                .updatedAt(bed.getUpdatedAt());

        Visit v = bed.getCurrentVisit();
        if (v != null) {
            b.currentVisitId(v.getId())
                    .currentVisitNumber(v.getVisitNumber())
                    .currentPatientName(
                            v.getPatient() != null
                                    ? (v.getPatient().getFirstName() + " " + v.getPatient().getLastName()).trim()
                                    : null)
                    .currentTriageCategory(
                            v.getCurrentTriageCategory() != null
                                    ? v.getCurrentTriageCategory().name()
                                    : null)
                    .currentTewsScore(v.getCurrentTewsScore())
                    // Placement timestamp isn't stored separately — the bed's
                    // updatedAt reflects the most-recent status change, which
                    // is the placement when status == OCCUPIED.
                    .currentPlacedAt(bed.getUpdatedAt());
        }

        if (assignedDevice != null) {
            b.assignedDeviceId(assignedDevice.getId())
                    .assignedDeviceName(assignedDevice.getDeviceName())
                    .assignedDeviceStatus(
                            assignedDevice.getStatus() != null ? assignedDevice.getStatus().name() : null);
        }

        if (activeSessionId != null) {
            b.activeSessionId(activeSessionId);
        }

        return b.build();
    }

    public static BedResponse toResponse(Bed bed) {
        return toResponse(bed, null, null);
    }
}
