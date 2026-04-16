package com.smartTriage.smartTriage_server.module.shift.mapper;

import com.smartTriage.smartTriage_server.module.shift.dto.ShiftTemplateAssignmentDto;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftTemplateResponse;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplate;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplateAssignment;
import com.smartTriage.smartTriage_server.module.user.entity.User;

import java.util.Collections;
import java.util.List;

public final class ShiftTemplateMapper {

    private ShiftTemplateMapper() {
    }

    public static ShiftTemplateAssignmentDto toAssignmentDto(ShiftTemplateAssignment row) {
        User user = row.getUser();
        return ShiftTemplateAssignmentDto.builder()
                .id(row.getId())
                .userId(user != null ? user.getId() : null)
                .userName(user != null ? (user.getFirstName() + " " + user.getLastName()) : null)
                .userEmail(user != null ? user.getEmail() : null)
                .zone(row.getZone())
                .shiftFunction(row.getShiftFunction())
                .isShiftLead(row.isShiftLead())
                .build();
    }

    public static ShiftTemplateResponse toResponse(ShiftTemplate template) {
        List<ShiftTemplateAssignmentDto> rows = template.getAssignments() == null
                ? Collections.emptyList()
                : template.getAssignments().stream()
                        .filter(ShiftTemplateAssignment::isActive)
                        .map(ShiftTemplateMapper::toAssignmentDto)
                        .toList();

        return ShiftTemplateResponse.builder()
                .id(template.getId())
                .hospitalId(template.getHospital() != null ? template.getHospital().getId() : null)
                .name(template.getName())
                .description(template.getDescription())
                .shiftPeriod(template.getShiftPeriod())
                .active(template.isActive())
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .assignments(rows)
                .build();
    }
}
