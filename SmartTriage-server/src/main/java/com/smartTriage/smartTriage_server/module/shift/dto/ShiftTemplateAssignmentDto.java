package com.smartTriage.smartTriage_server.module.shift.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

/**
 * A single row inside a shift template — used both for reading (response body)
 * and for writing (create/update request body). The parent template request
 * carries a list of these.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftTemplateAssignmentDto {

    /** Nullable on create — the server assigns UUIDs when rows are saved. */
    private UUID id;

    @NotNull(message = "User ID is required")
    private UUID userId;

    /** Read-only enrichment (ignored on request). */
    private String userName;
    /** Read-only enrichment (ignored on request). */
    private String userEmail;

    @NotNull(message = "Zone is required")
    private EdZone zone;

    @NotNull(message = "Shift function is required")
    private ShiftFunction shiftFunction;

    @JsonProperty("isShiftLead")
    @Builder.Default
    private boolean isShiftLead = false;
}
