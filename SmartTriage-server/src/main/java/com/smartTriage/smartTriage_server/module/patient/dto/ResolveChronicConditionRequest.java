package com.smartTriage.smartTriage_server.module.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mark a previously-recorded chronic condition as RESOLVED. The row
 * is not hard-deleted — resolution is itself an audit event. A
 * reason is required so the next clinician understands why the
 * safety engine no longer gates on this condition.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveChronicConditionRequest {

    @NotBlank(message = "Resolve reason is required")
    @Size(min = 5, max = 500)
    private String reason;

    @Size(max = 200)
    private String resolvedByName;
}
