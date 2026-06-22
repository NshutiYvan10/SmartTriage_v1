package com.smartTriage.smartTriage_server.module.medsafety.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for overriding a medication safety check.
 *
 * The overriding clinician is NOT taken from the request — it is resolved from
 * the authenticated principal server-side, so the forensic "overridden by" can
 * never be spoofed by the client.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OverrideSafetyCheckRequest {

    @NotBlank(message = "Override reason is required for a safety check override")
    private String reason;
}
