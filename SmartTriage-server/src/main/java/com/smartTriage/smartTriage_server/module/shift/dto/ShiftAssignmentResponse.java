package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftAssignmentResponse {

    private UUID id;
    private UUID hospitalId;
    private LocalDate shiftDate;
    private ShiftPeriod shiftPeriod;
    private UUID userId;
    private String userName;
    private String userEmail;
    private Role userRole;
    private Designation userDesignation;
    private String userDesignationLabel;
    private EdZone zone;
    private ShiftFunction shiftFunction;
    private Instant startedAt;
    private Instant endedAt;
    private boolean active;
    /**
     * Shift-lead badge. Exactly one active assignment per (hospital, shiftDate,
     * shiftPeriod) may carry this flag. Serialized as {@code isShiftLead} to
     * match the frontend contract.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isShiftLead")
    private boolean isShiftLead;
}
