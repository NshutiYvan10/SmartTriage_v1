package com.smartTriage.smartTriage_server.module.bed.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial update for a bed (admin action). All fields are optional — only
 * those provided are applied. Status is intentionally NOT updatable here:
 * status transitions go through the dedicated workflow endpoints
 * (place / discharge / mark-cleaned / mark-out-of-service) so the server
 * always applies the correct side-effects (monitoring session lifecycle).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBedRequest {

    @Size(max = 20)
    private String code;

    @Size(max = 100)
    private String label;

    private Boolean hasMonitor;

    private Integer displayOrder;

    private String notes;
}
