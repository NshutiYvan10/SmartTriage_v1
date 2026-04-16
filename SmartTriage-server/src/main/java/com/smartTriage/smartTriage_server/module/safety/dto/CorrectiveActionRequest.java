package com.smartTriage.smartTriage_server.module.safety.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Request DTO for planning a corrective action.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CorrectiveActionRequest {

    @NotBlank(message = "Corrective action description is required")
    private String correctiveAction;

    @NotBlank(message = "Corrective action owner is required")
    private String correctiveActionOwner;

    private Instant correctiveActionDeadline;
    private String preventiveMeasures;
}
