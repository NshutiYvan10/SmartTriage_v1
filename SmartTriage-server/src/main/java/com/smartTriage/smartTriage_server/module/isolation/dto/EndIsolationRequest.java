package com.smartTriage.smartTriage_server.module.isolation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for ending / clearing isolation. A reason is mandatory so every
 * de-isolation is auditable (e.g. lab-confirmed exclusion, criteria no longer met).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndIsolationRequest {

    @NotBlank(message = "A clearance reason is required to end isolation")
    private String reason;
}
