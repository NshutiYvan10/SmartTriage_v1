package com.smartTriage.smartTriage_server.module.safety.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for closing a safety incident with lessons learned.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CloseIncidentRequest {

    @NotBlank(message = "Closed by name is required")
    private String closedByName;

    private String lessonsLearned;
}
