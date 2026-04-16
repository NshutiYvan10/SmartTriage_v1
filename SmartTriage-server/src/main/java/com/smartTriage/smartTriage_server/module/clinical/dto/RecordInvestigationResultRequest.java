package com.smartTriage.smartTriage_server.module.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to record investigation results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordInvestigationResultRequest {

    @NotNull(message = "Investigation ID is required")
    private UUID investigationId;

    @NotBlank(message = "Result is required")
    private String result;

    /** Whether the result is abnormal */
    private Boolean isAbnormal;

    /** Whether this is a critical value requiring immediate action */
    private Boolean isCritical;

    private String notes;
}
