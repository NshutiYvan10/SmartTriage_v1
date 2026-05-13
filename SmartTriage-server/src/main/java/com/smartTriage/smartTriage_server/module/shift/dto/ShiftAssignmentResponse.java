package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
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
    /**
     * Workflow 4 — additional zones this assignment covers beyond
     * the primary {@link #zone}. The frontend uses this list to:
     *   • subscribe to {@code /topic/alerts/{hospitalId}/{zone}}
     *     for each covered zone,
     *   • render covered-zone chips on the dashboard header,
     *   • include the union in the zone-filtered patient list query.
     * Empty set (not null) when single-zone coverage.
     */
    private Set<EdZone> additionalZones;
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
