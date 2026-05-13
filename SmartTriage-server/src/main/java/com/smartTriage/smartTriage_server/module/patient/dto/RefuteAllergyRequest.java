package com.smartTriage.smartTriage_server.module.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to mark a previously-recorded allergy as REFUTED. The
 * row is not hard-deleted — refute is itself an audit event. A
 * reason is required so the next clinician reviewing the chart
 * understands why the safety check no longer fires for this
 * allergen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefuteAllergyRequest {

    @NotBlank(message = "Refute reason is required")
    @Size(min = 5, max = 500, message = "Refute reason must be between 5 and 500 characters")
    private String reason;

    /** Display name of the clinician refuting. */
    @Size(max = 200)
    private String refutedByName;
}
