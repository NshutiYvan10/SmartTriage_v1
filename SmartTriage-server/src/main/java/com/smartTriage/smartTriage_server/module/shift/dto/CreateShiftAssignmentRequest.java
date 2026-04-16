package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateShiftAssignmentRequest {

    @NotNull(message = "User ID is required")
    private UUID userId;

    @NotNull(message = "Zone is required")
    private EdZone zone;

    @NotNull(message = "Shift function is required")
    private ShiftFunction shiftFunction;

    /**
     * Whether this assignment should also carry the shift-lead badge.
     * Optional — defaults to false. Setting it true transfers the badge to
     * this user and clears it from any other holder for the same shift.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isShiftLead")
    private Boolean isShiftLead;
}
