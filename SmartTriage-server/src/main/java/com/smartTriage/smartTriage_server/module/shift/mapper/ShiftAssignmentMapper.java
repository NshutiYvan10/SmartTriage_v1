package com.smartTriage.smartTriage_server.module.shift.mapper;

import com.smartTriage.smartTriage_server.module.shift.dto.ShiftAssignmentResponse;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;

public final class ShiftAssignmentMapper {

    private ShiftAssignmentMapper() {
    }

    public static ShiftAssignmentResponse toResponse(ShiftAssignment entity) {
        return ShiftAssignmentResponse.builder()
                .id(entity.getId())
                .hospitalId(entity.getHospital().getId())
                .shiftDate(entity.getShiftDate())
                .shiftPeriod(entity.getShiftPeriod())
                .userId(entity.getUser().getId())
                .userName(entity.getUser().getFirstName() + " " + entity.getUser().getLastName())
                .userEmail(entity.getUser().getEmail())
                .userRole(entity.getUser().getRole())
                .userDesignation(entity.getUser().getDesignation())
                .userDesignationLabel(entity.getUser().getDesignation() != null
                        ? entity.getUser().getDesignation().getLabel()
                        : null)
                .zone(entity.getZone())
                .shiftFunction(entity.getShiftFunction())
                .startedAt(entity.getStartedAt())
                .endedAt(entity.getEndedAt())
                .active(entity.isActive())
                .isShiftLead(entity.isShiftLead())
                .build();
    }
}
