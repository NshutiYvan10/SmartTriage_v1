package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftTemplateResponse {

    private UUID id;
    private UUID hospitalId;
    private String name;
    private String description;
    private ShiftPeriod shiftPeriod;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;

    /** The per-user rows that make up the template. */
    private List<ShiftTemplateAssignmentDto> assignments;
}
