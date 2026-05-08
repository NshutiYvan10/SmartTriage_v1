package com.smartTriage.smartTriage_server.module.lab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Senior tech rejects a pending result and bounces it back to the
 * junior who entered it. The reason is required because a rejection
 * costs the junior re-work — they need to know what to fix.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RejectVerificationRequest {

    @NotBlank(message = "A rejection reason is required (e.g. 'looks like a decimal slip — re-check tube').")
    private String reason;

    private String rejectedByName;
}
