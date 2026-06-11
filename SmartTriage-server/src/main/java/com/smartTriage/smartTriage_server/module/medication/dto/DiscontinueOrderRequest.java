package com.smartTriage.smartTriage_server.module.medication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Doctor actively stops an order (V67). Reason is mandatory — the
 * audit trail must answer "why was this stopped" without ambiguity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscontinueOrderRequest {

    @NotBlank(message = "A discontinue reason is required")
    @Size(max = 500)
    private String reason;

    /** Display-name fallback for the discontinuing clinician. */
    @Size(max = 255)
    private String discontinuedByName;
}
