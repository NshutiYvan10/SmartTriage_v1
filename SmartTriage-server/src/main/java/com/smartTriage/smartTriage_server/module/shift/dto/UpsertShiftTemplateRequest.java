package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Create-or-update payload for a shift template. Used by POST (create) and
 * PUT (replace) endpoints — the server treats the list of assignments as
 * canonical, so updating a template atomically replaces its roster.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpsertShiftTemplateRequest {

    @NotBlank(message = "Template name is required")
    @Size(max = 120, message = "Template name must be 120 characters or fewer")
    private String name;

    private String description;

    @NotNull(message = "Shift period is required")
    private ShiftPeriod shiftPeriod;

    @Valid
    @Builder.Default
    private List<ShiftTemplateAssignmentDto> assignments = new ArrayList<>();
}
