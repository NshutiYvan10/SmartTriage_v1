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

    /** FALLBACK ONLY — the service stamps the closer from the authenticated principal. */
    private String closedByName;

    /** Closure without lessons learned is just archiving — the register requires them. */
    @NotBlank(message = "Lessons learned are required to close an incident")
    private String lessonsLearned;
}
