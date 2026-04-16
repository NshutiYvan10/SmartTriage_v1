package com.smartTriage.smartTriage_server.module.bed.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Create a new bed inside a zone (admin action).
 *
 * The code (e.g. "R1", "A3") is the compact identifier shown on bed-grid
 * tiles and must be unique per hospital. Convention is zone-prefix + number
 * so staff can tell at a glance which zone the bed belongs to, but the
 * system does not enforce the prefix — admins are free to use local
 * naming conventions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBedRequest {

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @NotNull(message = "Zone is required")
    private EdZone zone;

    @NotBlank(message = "Bed code is required")
    @Size(max = 20)
    private String code;

    @Size(max = 100)
    private String label;

    /**
     * Declares whether a permanently-mounted monitor lives at this bed.
     * Used by the UI to hint that auto-pairing will happen on placement.
     */
    private boolean hasMonitor;

    /** Display order within the zone (lower = shown first). */
    private Integer displayOrder;

    private String notes;
}
